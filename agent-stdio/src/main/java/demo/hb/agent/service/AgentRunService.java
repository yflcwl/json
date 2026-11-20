package demo.hb.agent.service;

import io.agentscope.core.skill.repository.AgentSkillRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;

@Service
public final class AgentRunService {

    private final HtmlGenerationService generation;
    private final AgentSkillRepository skills;
    private final AtomicBoolean running = new AtomicBoolean();

    public AgentRunService(HtmlGenerationService generation, AgentSkillRepository skills) {
        this.generation = generation;
        this.skills = skills;
    }

    public void run(Dispatch dispatch) {
        if (dispatch == null || dispatch.canvasId() == null || dispatch.uiUrl() == null
                || dispatch.page() == null) {
            throw new Failure("INVALID_INPUT");
        }
        String message = dispatch.message() == null ? "" : dispatch.message();
        List<String> images = dispatch.images() == null ? List.of() : dispatch.images().stream()
                .filter(image -> image != null && !image.isBlank())
                .toList();
        if ((message.isBlank() && images.isEmpty()) || images.size() > 3) {
            throw new Failure("INVALID_INPUT");
        }
        String skillId = dispatch.skillId() == null ? null : dispatch.skillId().trim();
        if (skillId != null && !skillId.isEmpty()) {
            if (skills.getAllSkills().stream().noneMatch(skill -> skillId.equals(skill.getSkillId()))) {
                throw new Failure("INVALID_INPUT");
            }
        }
        dispatch = new Dispatch(dispatch.canvasId(), message, dispatch.page(), dispatch.uiUrl(),
                skillId == null || skillId.isEmpty() ? null : skillId, images.isEmpty() ? null : images);
        if (!running.compareAndSet(false, true)) {
            throw new Failure("AGENT_UNAVAILABLE");
        }
        try {
            generation.generate(dispatch.canvasId().toString(), dispatch.message(), dispatch.page(),
                    dispatch.uiUrl(), dispatch.skillId(), dispatch.images());
        } finally {
            running.set(false);
        }
    }

    public record Dispatch(UUID canvasId, String message, String page, String uiUrl, String skillId,
                           List<String> images) {
    }

    public static final class Failure extends RuntimeException {
        private final String code;

        public Failure(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
