package demo.hb.agent.service;

import demo.hb.agent.config.AgentSettings;
import demo.hb.agent.service.AgentRunService.Failure;
import io.agentscope.core.agent.AgentRun;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.harness.agent.HarnessAgent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HtmlGenerationService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(HtmlGenerationService.class);
    private final AgentSettings settings;
    private final HarnessAgent agent;

    public HtmlGenerationService(AgentSettings settings, HarnessAgent agent) {
        this.settings = settings;
        this.agent = agent;
    }

    public void generate(String canvasId, String message, String page, String uiUrl, String skillId,
                         List<String> images) {
        if (agent == null) {
            throw new Failure("MODEL_NOT_CONFIGURED");
        }
        String runId = UUID.randomUUID().toString();
        var context = RuntimeContext.builder().userId("sketch-workspace")
                .sessionId(canvasId).runId(runId).build();
        var task = new StringBuilder(settings.userPrompt());
        task.append("\n目标画布 canvasId：").append(canvasId);
        task.append("\n画布页面地址：").append(uiUrl);
        task.append("\n用户要求：").append(message);
        if (images != null && !images.isEmpty()) {
            task.append("\n用户上传图片：").append(images.size())
                    .append(" 张（见本条消息的图片内容块，先识别图片再操作画布）。");
        }
        if (skillId != null && !skillId.isBlank()) {
            task.append("\n用户已明确指定 Skill：").append(skillId);
            task.append("\n在执行用户任务之前，必须先调用 load_skill_through_path，参数 skillId=「")
                    .append(skillId).append("」、path=「SKILL.md」；加载失败时停止执行并返回错误。");
        }
        var content = new ArrayList<ContentBlock>();
        content.add(TextBlock.builder().text(task.toString()).build());
        if (images != null) {
            for (String image : images) {
                content.add(imageBlock(image));
            }
        }
        var userMessage = Msg.builder().name("user").role(MsgRole.USER).content(content).build();
        try {
            Msg reply = AgentRun.create(agent.getAgentId(), runId,
                            () -> agent.call(List.of(userMessage), context))
                    .stream().single().timeout(Duration.ofSeconds(settings.timeoutSeconds())).block();
            if (reply == null) {
                throw new Failure("MODEL_FAILURE");
            }
            String text = reply.getContent().stream()
                    .filter(b -> b instanceof TextBlock)
                    .map(b -> ((TextBlock) b).getText())
                    .collect(java.util.stream.Collectors.joining("\n"));
            if (text.contains("maximum iterations") || text.contains("failed to generate")) {
                LOG.warn("Agent 迭代耗尽被强制总结 canvas={}", canvasId);
                throw new Failure("GENERATION_INCOMPLETE");
            }
            LOG.info("Agent 画布修改完成 canvas={}", canvasId);
        } catch (Failure ex) {
            throw ex;
        } catch (RuntimeException ex) {
            LOG.warn("Agent 执行失败 canvas={} error={}", canvasId, ex.getClass().getSimpleName());
            throw new Failure(reactor.core.Exceptions.unwrap(ex) instanceof TimeoutException
                    ? "GENERATION_TIMEOUT" : "MODEL_FAILURE");
        } finally {
            agent.clearStateCache("sketch-workspace", canvasId);
        }
    }

    @Override
    public void close() {
        if (agent != null) {
            agent.close();
        }
    }

    private static DataBlock imageBlock(String path) {
        Path file = Path.of(path);
        try {
            byte[] bytes = Files.readAllBytes(file);
            String name = file.getFileName().toString();
            return DataBlock.builder()
                    .source(Base64Source.builder()
                            .mediaType(mediaType(name))
                            .data(Base64.getEncoder().encodeToString(bytes))
                            .build())
                    .name(name)
                    .build();
        } catch (IOException ex) {
            throw new Failure("INVALID_INPUT");
        }
    }

    private static String mediaType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        return switch (dot < 0 ? "" : lower.substring(dot + 1)) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> throw new Failure("INVALID_INPUT");
        };
    }
}
