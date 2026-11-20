package demo.hb.agent.controller;

import demo.hb.agent.service.SessionViewService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 只读会话视图入口，与执行入口共用本地请求过滤器。 */
@RestController
public class SessionViewController {

    private final SessionViewService sessions;

    public SessionViewController(SessionViewService sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/internal/sessions/{canvasId}/history")
    public Map<String, Object> history(@PathVariable("canvasId") UUID canvasId) {
        return sessions.history(canvasId.toString());
    }

    @GetMapping("/internal/sessions/{canvasId}/rounds")
    public Map<String, Object> rounds(@PathVariable("canvasId") UUID canvasId) {
        return sessions.rounds(canvasId.toString());
    }

    @GetMapping("/internal/sessions/{canvasId}/rounds/{runId}")
    public Map<String, Object> round(@PathVariable("canvasId") UUID canvasId,
                                     @PathVariable("runId") String runId) {
        return sessions.round(canvasId.toString(), runId);
    }

    @DeleteMapping("/internal/sessions/{canvasId}")
    public void delete(@PathVariable("canvasId") UUID canvasId) throws java.io.IOException {
        sessions.delete(canvasId.toString());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalidArgument(IllegalArgumentException error) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "INVALID_INPUT"));
    }
}
