package demo.hb.agent.controller;

import demo.hb.agent.config.AgentSettings;
import demo.hb.agent.service.AgentRunService;
import demo.hb.agent.service.AgentRunService.Dispatch;
import demo.hb.agent.service.AgentRunService.Failure;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class AgentRunController {

    private final int maxDispatchBytes;
    private final AgentRunService runs;
    private final JsonMapper json;

    public AgentRunController(AgentRunService runs, JsonMapper json,
                              AgentSettings settings) {
        this.runs = runs;
        this.json = json;
        this.maxDispatchBytes = settings.maxDispatchBytes();
    }

    @PostMapping("/internal/runs")
    public ResponseEntity<?> dispatch(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(maxDispatchBytes + 1);
        if (body.length > maxDispatchBytes) {
            return error(HttpStatus.CONTENT_TOO_LARGE, "INVALID_INPUT");
        }
        Dispatch dispatch;
        try {
            dispatch = json.readValue(body, Dispatch.class);
        } catch (JacksonException | IllegalArgumentException ex) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        try {
            runs.run(dispatch);
            return ResponseEntity.ok().build();
        } catch (Failure ex) {
            return error("INVALID_INPUT".equals(ex.code()) ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY,
                    ex.code());
        }
    }

    private static ResponseEntity<?> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", code));
    }
}
