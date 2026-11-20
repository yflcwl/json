package demo.hb.agent.config;

import demo.hb.agent.session.SessionRepository;
import demo.hb.agent.tool.CanvasToolSupport;
import demo.hb.agent.tool.CanvasTools;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class HtmlAgentFactory {

    private static final int MAX_ITERS = 50;

    public static HarnessAgent create(AgentSettings settings, AgentSkillRepository skillRepository,
                                      SessionRepository sessions) {
        String key = settings.apiKey();
        if (key == null || key.isBlank()) {
            return null;
        }

        Path ego = egoBrowserPath();
        String shellPath = ego.getParent() + File.pathSeparator
                + System.getenv().getOrDefault("PATH", "");
        writePathMarker(ego);
        var filesystem = new LocalFilesystemSpec()
                .project(Path.of("."))
                .inheritEnv(true)
                .env("PATH", shellPath)
                .sharedLocalWorkspace(true);

        Toolkit toolkit = new Toolkit();
        var canvasSupport = new CanvasToolSupport(ego, Path.of("agent-state", "scripts"),
                Path.of("."), settings.uiUrl());
        toolkit.registerTool(new CanvasTools(canvasSupport));

        Model model = ModelRegistry.resolve(openAiCompatibleModel(settings.model()),
                ModelCreationContext.builder()
                        .apiKey(settings.apiKey())
                        .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                        .enableThinking(settings.enableThinking())
                        .stream(false)
                        .build());

        var compaction = settings.compaction();
        int triggerMessages = compaction != null
                ? compaction.triggerMessages() : 60;
        int keepMessages = compaction != null
                ? Math.max(2, compaction.keepMessages()) : 24;
        int triggerTokens = compaction != null && compaction.triggerTokens() > 0
                ? compaction.triggerTokens() : 15_000;
        var builder = HarnessAgent.builder()
                .name("html-agent")
                .sysPrompt(settings.systemPrompt())
                .model(model)
                .maxIters(MAX_ITERS)
                .enablePendingToolRecovery(true)
                .workspace(Path.of("agent-state"))
                .filesystem(filesystem)
                .toolkit(toolkit)
                .disableFilesystemTools()
                .disableWebTools()
                .disableShellTool()
                .disableSubagents()
                .disableMemoryTools()
                .disableMemoryHooks()
                .stateStore(sessions.states())
                .sessionLogStore(sessions.logs())
                .compaction(CompactionConfig.builder()
                    .triggerMessages(triggerMessages)
                    .triggerTokens(triggerTokens)
                    .keepMessages(keepMessages)
                    .keepTokens(0)
                    .flushBeforeCompact(false)
                    .build())
                .skillRepository(skillRepository);
        return builder.build();
    }

    /**
     * DashScope 的 native API 慢（实测 22-33s/轮），OpenAI 兼容端点快（0.3-2s）。
     * 统一把 dashscope: 前缀的模型改走 openai: 兼容通道（DashScope compatible-mode）。
     */
    private static String openAiCompatibleModel(String configured) {
        if (configured == null || configured.isBlank()) {
            return "openai:qwen-flash";
        }
        if (configured.startsWith("dashscope:")) {
            return "openai:" + configured.substring("dashscope:".length());
        }
        return configured;
    }

    private static Path egoBrowserPath() {
        Path ego = Path.of(System.getProperty("user.home"), ".local", "share", "ego");
        if (Files.isDirectory(ego)) {
            Path active = ego.resolve("active_version_dir");
            Path direct = findExecutable(active);
            if (direct != null) {
                return direct;
            }
            try (Stream<Path> dirs = Files.list(ego)) {
                for (Path dir : dirs.filter(Files::isDirectory)
                        .sorted(Comparator.comparing(Path::getFileName).reversed()).toList()) {
                    Path candidate = findExecutable(dir);
                    if (candidate != null) {
                        return candidate;
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return Path.of("ego-browser");
    }

    private static Path findExecutable(Path directory) {
        if (!Files.isDirectory(directory)) {
            return null;
        }
        for (Path candidate : List.of(directory.resolve("ego-browser.exe"), directory.resolve("ego-browser"))) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static void writePathMarker(Path ego) {
        try {
            Path scripts = Path.of("agent-state", "scripts");
            Files.createDirectories(scripts);
            Files.writeString(scripts.resolve("ego-browser-path.txt"),
                    ego.toAbsolutePath().normalize().toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("无法写入 ego-browser 路径标记文件", ex);
        }
    }
}
