package demo.hb.agent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import demo.hb.agent.config.AgentSettings;
import demo.hb.agent.service.AgentRunService.Dispatch;
import demo.hb.agent.service.AgentRunService.Failure;
import demo.hb.agent.session.SessionRepository;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class AgentRunServiceTest {

    @TempDir Path directory;
    private ClasspathSkillRepository skills;
    private AgentRunService runs;

    @BeforeEach
    void setUp() throws Exception {
        skills = new ClasspathSkillRepository("skills");
        runs = new AgentRunService(new HtmlGenerationService(null, null), skills);
    }

    @AfterEach
    void close() {
        skills.close();
    }

    @Test
    void rejectsInvalidDispatchBeforeExecution() {
        UUID id = UUID.randomUUID();
        List<Dispatch> invalid = Arrays.asList(
                null,
                new Dispatch(null, "move", "{}", "http://localhost", null, null),
                new Dispatch(id, "move", null, "http://localhost", null, null),
                new Dispatch(id, "move", "{}", null, null, null),
                new Dispatch(id, " ", "{}", "http://localhost", null, List.of()),
                new Dispatch(id, null, "{}", "http://localhost", null, Arrays.asList(null, " ")),
                new Dispatch(id, "move", "{}", "http://localhost", null, List.of("1", "2", "3", "4")),
                new Dispatch(id, "move", "{}", "http://localhost", "missing", null));
        for (Dispatch dispatch : invalid) {
            assertEquals("INVALID_INPUT", assertThrows(Failure.class, () -> runs.run(dispatch)).code());
        }
    }

    @Test
    void releasesExecutionSlotAfterFailure() {
        Dispatch dispatch = new Dispatch(UUID.randomUUID(), "move", "{}", "http://localhost", null, null);
        assertEquals("MODEL_NOT_CONFIGURED", assertThrows(Failure.class, () -> runs.run(dispatch)).code());
        assertEquals("MODEL_NOT_CONFIGURED", assertThrows(Failure.class, () -> runs.run(dispatch)).code());
    }

    @Test
    void normalizesSkillAndImageInputsBeforeModelCall() throws Exception {
        var input = new AtomicReference<List<Msg>>();
        Model model = new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                input.set(messages);
                return Flux.just(new ChatResponse("reply", List.of(TextBlock.builder().text("done").build()),
                        null, Map.of(), "stop"));
            }

            @Override
            public String getModelName() {
                return "test-model";
            }
        };
        var sessions = new SessionRepository(directory);
        try (var agent = HarnessAgent.builder().name("html-agent").model(model).workspace(directory)
                .stateStore(sessions.states()).sessionLogStore(sessions.logs())
                .disableFilesystemTools().disableShellTool().disableWebTools().disableSubagents()
                .disableMemoryTools().disableMemoryHooks().disableWorkspaceContext().disableCompaction()
                .disableDynamicSkills().disableDefaultWorkspaceSkills().build()) {
            var settings = new AgentSettings(null, "test-model", null, false, 10, 1048576,
                    "Edit the canvas", "请按用户要求修改画布。", "http://localhost",
                    new AgentSettings.Server("127.0.0.1", 8083), new AgentSettings.Compaction(true, 10, 40, 15000));
            var service = new AgentRunService(new HtmlGenerationService(settings, agent), skills);
            String skill = skills.getAllSkills().get(0).getSkillId();
            service.run(new Dispatch(UUID.randomUUID(), "move", "{}", "http://localhost",
                    "  " + skill + "  ", null));
            Msg user = input.get().stream().filter(msg -> msg.getRole() == MsgRole.USER).reduce((a, b) -> b).orElseThrow();
            assertTrue(user.getTextContent().contains("skillId=「" + skill + "」"));
            assertFalse(user.getTextContent().contains("用户上传图片"));

            Path image = directory.resolve("image.png");
            Files.write(image, new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
            service.run(new Dispatch(UUID.randomUUID(), null, "{}", "http://localhost", "  ",
                    Arrays.asList(null, " ", image.toString())));
            user = input.get().stream().filter(msg -> msg.getRole() == MsgRole.USER).reduce((a, b) -> b).orElseThrow();
            assertTrue(user.getTextContent().contains("用户上传图片：1 张"));
            assertFalse(user.getTextContent().contains("用户已明确指定 Skill"));
            assertEquals(1, user.getContent().stream().filter(DataBlock.class::isInstance).count());
        }
    }
}
