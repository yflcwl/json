package demo.hb.web.service;

import demo.hb.web.config.SketchProperties;
import demo.hb.web.dto.GenerateInput;
import demo.hb.web.exception.GenerationException;
import demo.hb.web.exception.GenerationException.Code;
import demo.hb.web.repository.CanvasRepository;
import demo.hb.web.service.AgentDispatchClient.AgentFailure;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class CanvasService {

    private static final Logger LOG = LoggerFactory.getLogger(CanvasService.class);
    private static final int MAX_MESSAGE = 4000;
    private static final int MAX_IMAGES = 3;
    private static final String EMPTY_PAGE = "{\"version\":1,\"canvasSize\":\"960x600\",\"components\":[]}";
    private final CanvasRepository repository;
    private final AgentDispatchClient agent;
    private final CanvasImages images;
    private final CanvasPageValidator validator;
    private final String uiUrl;
    private boolean busy;

    public CanvasService(CanvasRepository repository, AgentDispatchClient agent, CanvasPageValidator validator,
                         SketchProperties properties, CanvasImages images) {
        this.repository = repository;
        this.agent = agent;
        this.images = images;
        this.validator = validator;
        this.uiUrl = properties.uiUrl();
    }

    public synchronized String create() {
        UUID id = UUID.randomUUID();
        repository.write(id, EMPTY_PAGE.getBytes(StandardCharsets.UTF_8));
        LOG.info("已新建画布 canvas={}", id);
        return id.toString();
    }

    public String page(UUID id) {
        return new String(repository.read(id), StandardCharsets.UTF_8);
    }

    public synchronized void savePage(UUID id, String page) {
        validator.validate(page);
        repository.write(id, page.getBytes(StandardCharsets.UTF_8));
    }

    public List<CanvasRepository.CanvasInfo> list() {
        return repository.list();
    }

    public void generate(UUID id, GenerateInput input) {
        String message = input.message() == null ? "" : input.message().strip();
        List<String> images = input.images() == null ? List.of() : input.images();
        if (images.size() > MAX_IMAGES) {
            throw new GenerationException(Code.INVALID_INPUT, "最多支持 3 张图片。");
        }
        if (message.isBlank() && images.isEmpty()) {
            throw new GenerationException(Code.INVALID_INPUT, "请输入修改要求或上传图片。");
        }
        if (message.length() > MAX_MESSAGE) {
            throw new GenerationException(Code.INVALID_INPUT, "修改要求最多可输入 4000 个字符。");
        }
        synchronized (this) {
            if (busy) {
                throw new GenerationException(Code.GENERATION_BUSY, "已有画布修改正在进行，请稍后再试。");
            }
            busy = true;
        }
        try {
            String base = page(id);
            List<String> imagePaths = this.images.resolve(id, images);
            String url = uiUrl + (uiUrl.contains("?") ? "&" : "?") + "canvas=" + id;
            LOG.info("画布修改请求 canvas={} messageChars={} images={}", id, message.length(), imagePaths.size());
            agent.generate(id, message, base, url, input.skillId(), imagePaths);
            LOG.info("画布修改完成 canvas={}", id);
        } catch (AgentFailure ex) {
            throw agentFailure(ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new GenerationException(Code.AGENT_UNAVAILABLE, "服务正在关闭，请重新提交。");
        } finally {
            synchronized (this) {
                busy = false;
            }
        }
    }

    public JsonNode history(UUID id) {
        return read(() -> agent.history(id));
    }

    public JsonNode rounds(UUID id) {
        return read(() -> agent.rounds(id));
    }

    public JsonNode round(UUID id, String runId) {
        return read(() -> agent.round(id, runId));
    }

    public JsonNode skills() {
        return read(() -> agent.skills());
    }

    public synchronized void delete(UUID id) {
        if (busy) {
            throw new GenerationException(Code.GENERATION_BUSY, "正在修改画布，请稍后再试。");
        }
        repository.delete(id);
        try {
            agent.deleteSession(id);
            LOG.info("已删除画布及会话 canvas={}", id);
        } catch (IOException | InterruptedException | AgentFailure ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("画布已删除，但清理会话失败 canvas={} error={}", id, ex.getClass().getSimpleName());
        }
    }

    private JsonNode read(AgentRead read) {
        try {
            return read.call();
        } catch (AgentFailure ex) {
            throw agentFailure(ex.getMessage());
        } catch (IOException ex) {
            throw new GenerationException(Code.AGENT_UNAVAILABLE, "Agent 无法连接，请检查 Agent 进程后重新提交。");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new GenerationException(Code.AGENT_UNAVAILABLE, "服务正在关闭，请重新提交。");
        }
    }

    private interface AgentRead {
        JsonNode call() throws IOException, InterruptedException;
    }

    static GenerationException agentFailure(String code) {
        return switch (code) {
            case "AGENT_UNAVAILABLE" ->
                    new GenerationException(Code.AGENT_UNAVAILABLE, "Agent 无法连接，请检查 Agent 进程后重新提交。");
            case "GENERATION_TIMEOUT" ->
                    new GenerationException(Code.GENERATION_TIMEOUT, "生成超时，请稍后重试。");
            case "MODEL_NOT_CONFIGURED" ->
                    new GenerationException(Code.MODEL_NOT_CONFIGURED, "请配置 DASHSCOPE_API_KEY 后重启 Agent。");
            case "INVALID_INPUT" ->
                    new GenerationException(Code.INVALID_INPUT, "Agent 收到的生成参数无效。");
            default -> new GenerationException(Code.MODEL_FAILURE, "模型调用失败，请检查 Agent 日志。");
        };
    }
}
