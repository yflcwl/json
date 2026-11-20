package demo.hb.web.service;

import demo.hb.web.config.SketchProperties;
import demo.hb.web.exception.GenerationException;
import demo.hb.web.exception.GenerationException.Code;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class AgentDispatchClient {

    private static final Logger LOG = LoggerFactory.getLogger(AgentDispatchClient.class);
    private final HttpClient http;
    private final SketchProperties.Agent settings;
    private final JsonMapper json;

    public AgentDispatchClient(SketchProperties properties, JsonMapper json) {
        this.settings = properties.agent();
        this.http = HttpClient.newBuilder().connectTimeout(settings.connectTimeout()).build();
        this.json = json;
    }

    public void generate(UUID canvasId, String message, String page, String uiUrl, String skillId,
                         List<String> images) throws InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("canvasId", canvasId.toString());
        payload.put("message", message);
        payload.put("page", page);
        payload.put("uiUrl", uiUrl);
        if (skillId != null && !skillId.isBlank()) {
            payload.put("skillId", skillId);
        }
        if (images != null && !images.isEmpty()) {
            payload.put("images", images);
        }
        var request = HttpRequest.newBuilder(settings.url().resolve("/internal/runs"))
                .timeout(settings.requestTimeout()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(payload)))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                return;
            }
            String code = errorCode(response);
            LOG.warn("Agent 生成未成功 canvas={} status={} error={}", canvasId, response.statusCode(), code);
            throw new AgentFailure(code == null ? "MODEL_FAILURE" : code);
        } catch (IOException ex) {
            LOG.warn("Agent 派发连接失败 canvas={} error={}", canvasId, ex.getClass().getSimpleName());
            throw new AgentFailure("AGENT_UNAVAILABLE");
        }
    }

    public JsonNode history(UUID canvasId) throws IOException, InterruptedException {
        return read(settings.url().resolve("/internal/sessions/" + canvasId + "/history"));
    }

    public JsonNode rounds(UUID canvasId) throws IOException, InterruptedException {
        return read(settings.url().resolve("/internal/sessions/" + canvasId + "/rounds"));
    }

    public JsonNode round(UUID canvasId, String runId) throws IOException, InterruptedException {
        return read(settings.url().resolve(
                "/internal/sessions/" + canvasId + "/rounds/" + runId));
    }

    public JsonNode skills() throws IOException, InterruptedException {
        return readAny(settings.url().resolve("/internal/skills"));
    }

    public void deleteSession(UUID canvasId) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(
                        settings.url().resolve("/internal/sessions/" + canvasId))
                .timeout(java.time.Duration.ofSeconds(30)).DELETE().build();
        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200 && response.statusCode() != 404) {
            throw new AgentFailure("AGENT_UNAVAILABLE");
        }
    }

    private JsonNode read(java.net.URI url) throws IOException, InterruptedException {
        JsonNode body = readAny(url);
        if (!body.isObject()) throw new AgentFailure("MODEL_FAILURE");
        return body;
    }

    private JsonNode readAny(java.net.URI url) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(url).timeout(settings.requestTimeout()).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            String code = errorCode(response);
            throw new AgentFailure(code == null ? "AGENT_UNAVAILABLE" : code);
        }
        try {
            JsonNode body = json.readTree(response.body());
            if (body == null) throw new AgentFailure("MODEL_FAILURE");
            return body;
        } catch (JacksonException ex) {
            throw new AgentFailure("MODEL_FAILURE");
        }
    }

    private String errorCode(HttpResponse<String> response) {
        try {
            JsonNode body = json.readTree(response.body());
            JsonNode error = body == null ? null : body.get("error");
            return error == null ? null : error.asText(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public static final class AgentFailure extends RuntimeException {
        public AgentFailure(String code) {
            super(code);
        }
    }
}
