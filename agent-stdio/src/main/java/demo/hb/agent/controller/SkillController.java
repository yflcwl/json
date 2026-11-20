package demo.hb.agent.controller;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 只读 Skill 目录入口，与执行入口共用本地请求过滤器。 */
@RestController
public class SkillController {

    private final AgentSkillRepository skills;

    public SkillController(AgentSkillRepository skills) {
        this.skills = skills;
    }

    @GetMapping("/internal/skills")
    public List<Map<String, Object>> skills() {
        return skills.getAllSkills().stream()
                .map(SkillController::toView)
                .toList();
    }

    private static Map<String, Object> toView(AgentSkill skill) {
        return Map.of(
                "id", skill.getSkillId(),
                "name", skill.getName(),
                "description", skill.getDescription() == null ? "" : skill.getDescription());
    }
}
