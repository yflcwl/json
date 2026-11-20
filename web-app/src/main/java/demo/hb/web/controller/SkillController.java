package demo.hb.web.controller;

import demo.hb.web.service.CanvasService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 转发 Agent 端已注册的 Skill 列表。 */
@RestController
public class SkillController {

    private final CanvasService service;

    public SkillController(CanvasService service) {
        this.service = service;
    }

    @GetMapping(path = "/api/skills", produces = MediaType.APPLICATION_JSON_VALUE)
    public JsonNode skills() {
        return service.skills();
    }
}
