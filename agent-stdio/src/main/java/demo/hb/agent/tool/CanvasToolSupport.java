package demo.hb.agent.tool;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 画布操作工具的共享执行基建：画布页面绑定（初始化/复用）、脚本拼装、进程执行、
 * 结果解析。每个画布操作工具只生成自己的操作 JS，执行链路全部走这里。
 */
public final class CanvasToolSupport {

    private static final Logger LOG = LoggerFactory.getLogger(CanvasToolSupport.class);
    private static final int DEFAULT_TIMEOUT_MS = 180_000;
    private static final int MAX_TIMEOUT_MS = 600_000;
    private static final int MAX_STREAM_CHARS = 3_000;
    private static final int KEEP_SCRIPTS = 50;
    private static final Pattern RESULT_MARKER =
            Pattern.compile("__HB_RESULT__\\s*(\\{.*)\\s*$", Pattern.MULTILINE);
    private static final Pattern BIND_MARKER =
            Pattern.compile("__HB_BIND__\\s*(\\{.*?\\})\\s*$", Pattern.MULTILINE);

    private final Path executable;
    private final Path scriptDir;
    private final Path workingDir;
    private final String uiUrl;

    /**
     * canvasId → 画布页面绑定（spaceId + 页面标签）。首次初始化后缓存，后续脚本直接复用。
     */
    private final ConcurrentMap<String, Binding> bindings = new ConcurrentHashMap<>();

    /**
     * 每个画布一把锁：同一画布的操作串行执行，避免并发工具产生鼠标动作冲突。
     */
    private final ConcurrentMap<String, java.util.concurrent.locks.ReentrantLock> canvasLocks = new ConcurrentHashMap<>();

    /**
     * canvasId → 空间重建序号。invalidate 后序号 +1，初始化时用带序号的全新空间名。
     */
    private final ConcurrentMap<String, Integer> rebuildSeq = new ConcurrentHashMap<>();

    private record Binding(int spaceId, String pageLabel) {
    }

    public CanvasToolSupport(Path executable, Path scriptDir, Path workingDir, String uiUrl) {
        this.executable = executable;
        this.scriptDir = scriptDir;
        this.workingDir = workingDir;
        this.uiUrl = uiUrl == null || uiUrl.isBlank() ? "http://127.0.0.1:8086" : uiUrl;
    }

    /**
     * 执行一次画布操作：同一画布串行化，拼装（绑定前缀 + 操作 JS）→ 落盘 → 执行 → 解析结果。
     */
    public ToolResultBlock run(String canvasId, String operationJs, Integer timeoutMs) {
        return run(canvasId, operationJs, timeoutMs, true);
    }

    /**
     * 丢弃当前画布的页面绑定（空间/页面缓存）。下次操作走初始化路径，
     * 用新名字创建全新任务空间并重新打开画布页。组件数据不受影响（存服务端）。
     */
    public void invalidate(String canvasId) {
        if (canvasId == null) return;
        rebuildSeq.merge(canvasId, 1, Integer::sum);
        if (bindings.remove(canvasId) != null) {
            LOG.info("画布页面绑定被主动丢弃 canvas={}", canvasId);
        }
    }

    /**
     * requiresEdit=true 时注入编辑环境前置保证：页面切回编辑模式、展开组件库/底部/画布面板。
     * 只读操作（read_canvas）传 false，不改变页面状态。
     */
    public ToolResultBlock run(String canvasId, String operationJs, Integer timeoutMs, boolean requiresEdit) {
        if (canvasId == null || canvasId.isBlank()) {
            return fail("CANVAS_ID_MISSING: 执行上下文缺少目标画布 ID");
        }
        var lock = canvasLocks.computeIfAbsent(canvasId,
                key -> new java.util.concurrent.locks.ReentrantLock());
        lock.lock();
        try {
            return doRun(canvasId, operationJs, timeoutMs, requiresEdit);
        } finally {
            lock.unlock();
        }
    }

    private ToolResultBlock doRun(String canvasId, String operationJs, Integer timeoutMs, boolean requiresEdit) {
        if (operationJs == null || operationJs.isBlank()) {
            return fail("OPERATION_MISSING: 操作代码为空");
        }
        int timeout = DEFAULT_TIMEOUT_MS;
        if (timeoutMs != null) {
            timeout = Math.max(1_000, Math.min(MAX_TIMEOUT_MS, timeoutMs));
        }
        long t0 = System.nanoTime();

        Binding binding = bindings.get(canvasId);
        boolean firstBind = binding == null;
        String fullScript = (firstBind
                ? initPrologue(canvasId)
                : reusePrologue(canvasId, binding))
                + (requiresEdit ? EDIT_GUARD : "")
                + (firstBind && requiresEdit ? PROBE_ONCE : "")
                + operationJs + "\n";
        long tAssembled = System.nanoTime();

        Path file;
        try {
            Files.createDirectories(scriptDir);
            file = scriptDir.resolve("round-" + System.currentTimeMillis() + "-"
                    + Integer.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextInt(0x1000, 0xffff))
                    + ".mjs");
            Files.writeString(file, fullScript, StandardCharsets.UTF_8);
            pruneScripts();
        } catch (IOException ex) {
            LOG.warn("写入 ego 脚本失败: {}", ex.getMessage());
            return fail("SCRIPT_WRITE_FAILED: " + ex.getMessage());
        }
        long tWritten = System.nanoTime();

        Process process = null;
        try {
            // <executable> nodejs < file
            ProcessBuilder builder = new ProcessBuilder(executable.toString(), "nodejs");
            builder.directory(workingDir.toFile());
            builder.redirectInput(file.toFile());
            process = builder.start();

            long tStarted = System.nanoTime();
            CompletableFuture<String> stdout = readAsync(process.getInputStream());
            CompletableFuture<String> stderr = readAsync(process.getErrorStream());

            boolean finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
            long tDone = System.nanoTime();
            if (!finished) {
                process.destroyForcibly();
                LOG.info("画布工具超时 canvas={} 执行={}ms", canvasId, ms(tStarted, tDone));
                return fail("执行超时(" + timeout + "ms)。stdout:\n" + safeGet(stdout)
                        + "\nstderr:\n" + safeGet(stderr));
            }

            int exitCode = process.exitValue();
            String stdoutText = safeGet(stdout);
            String stderrText = safeGet(stderr);
            manageBinding(canvasId, exitCode, stdoutText, stderrText);
            LOG.info("画布工具耗时 canvas={} 拼装={}ms 落盘={}ms 启动={}ms 执行={}ms 总={}ms exit={}",
                    canvasId, ms(t0, tAssembled), ms(tAssembled, tWritten),
                    ms(tWritten, tStarted), ms(tStarted, tDone), ms(t0, tDone), exitCode);
            if (exitCode != 0) {
                return fail("exitCode=" + exitCode + "\nstdout:\n" + stdoutText
                        + "\nstderr:\n" + stderrText);
            }
            Matcher marker = RESULT_MARKER.matcher(stdoutText + "\n" + stderrText);
            if (marker.find()) {
                return ToolResultBlock.of(TextBlock.builder().text(marker.group(1)).build());
            }
            return ToolResultBlock.of(TextBlock.builder()
                    .text("stdout:\n" + stdoutText + "\nstderr:\n" + stderrText).build());
        } catch (IOException ex) {
            LOG.warn("启动 ego-browser 失败: {}", ex.getMessage());
            return fail("EXEC_START_FAILED: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return fail("INTERRUPTED");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static long ms(long fromNanos, long toNanos) {
        return (toNanos - fromNanos) / 1_000_000;
    }

    private void manageBinding(String canvasId, int exitCode, String stdout, String stderr) {
        Binding current = bindings.get(canvasId);
        if (exitCode == 0 && current == null) {
            Matcher marker = BIND_MARKER.matcher(stdout + "\n" + stderr);
            if (marker.find()) {
                Binding parsed = parseBinding(marker.group(1));
                if (parsed != null) {
                    bindings.put(canvasId, parsed);
                    LOG.info("画布页面绑定建立 canvas={} space={} label={}",
                            canvasId, parsed.spaceId(), parsed.pageLabel());
                }
            }
        }
    }

    private static Binding parseBinding(String json) {
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            return new Binding(node.get("spaceId").asInt(), node.get("label").asText());
        } catch (Exception ex) {
            LOG.warn("解析画布绑定信息失败: {}", ex.getMessage());
            return null;
        }
    }

    private static ToolResultBlock fail(String text) {
        return ToolResultBlock.error(text);
    }

    /**
     * 编辑环境前置保证：页面必须处于编辑模式、关键面板展开，编辑操作才会被前端接受。
     */
    private static final String EDIT_GUARD = """
            // ===== 工具注入：编辑环境前置保证 =====
            const __view = await page.evaluate(() => ({
                mode: document.body.dataset.mode || 'edit',
                folds: ['library', 'inspector', 'canvas']
                    .filter(name => document.body.getAttribute('data-fold-' + name) === 'true'),
                propsHidden: document.getElementById('properties-panel')?.hidden
            }));
            if (__view.mode !== 'edit') {
                await page.click('button[data-mode="edit"]');
            }
            for (const name of __view.folds) {
                await page.click('.panel-fold[data-fold="' + name + '"]');
            }
            if (__view.propsHidden) {
                await page.click('.bottom-tabs button[data-panel="properties"]');
            }
            if (__view.mode !== 'edit' || __view.folds.length || __view.propsHidden) {
                await page.waitForTimeout(250);
            }
            
            // 输入通道探针函数：每次运行前清零计数（避免累计误判）。
            const __probe = async () => {
                await page.evaluate(() => {
                    if (window.__hbProbeReady) {
                        window.__hbProbe.down = 0;
                        window.__hbProbe.move = 0;
                        window.__hbProbe.up = 0;
                        return;
                    }
                    window.__hbProbeReady = true;
                    window.__hbProbe = { down: 0, move: 0, up: 0 };
                    document.addEventListener('pointerdown', () => window.__hbProbe.down++, true);
                    document.addEventListener('pointermove', () => window.__hbProbe.move++, true);
                    document.addEventListener('pointerup', () => window.__hbProbe.up++, true);
                });
                const p = await page.evaluate(() => {
                    const r = document.querySelector('.canvas-footer').getBoundingClientRect();
                    return { x: r.left + r.width / 2, y: r.top + r.height / 2, x2: r.left + r.width / 2 + 20 };
                });
                await page.mouse.move(p.x, p.y);
                await page.mouse.down();
                await page.mouse.move(p.x2, p.y, { steps: 3 });
                await page.mouse.up();
                await page.waitForTimeout(150);
                return page.evaluate(() => ({ down: window.__hbProbe.down, move: window.__hbProbe.move, up: window.__hbProbe.up }));
            };
            
            """;

    /**
     * 绑定建立时执行一次的输入通道检查：探针失败则 reload 恢复，仍失败则明确报错。
     */
    private static final String PROBE_ONCE = """
            // ===== 工具注入：输入通道检查（绑定建立时一次） =====
            let __probeResult = await __probe();
            if (__probeResult.down < 1 || __probeResult.move < 2 || __probeResult.up < 1) {
                await page.reload();
                await page.waitForSelector('.canvas-component, #canvas', { timeout: 15000 });
                __probeResult = await __probe();
                if (__probeResult.down < 1 || __probeResult.move < 2 || __probeResult.up < 1) {
                    throw new Error('输入通道不可用：页面指针事件管线异常，reload 后仍未恢复');
                }
            }
            
            """;

    /**
     * 首次初始化：创建或恢复该画布的专属空间，绑定明确页面，打开目标画布。
     */
    private String initPrologue(String canvasId) {
        String id = quoteJs(canvasId);
        String url = quoteJs(uiUrl + "?canvas=" + canvasId);
        String base = "hb-canvas-" + canvasId;
        int seq = rebuildSeq.getOrDefault(canvasId, 0);
        String spaceName = quoteJs(seq > 0 ? base + "-r" + seq : base);
        return """
                // ===== 工具注入：画布页面绑定初始化 =====
                const __canvasId = %s;
                const __canvasUrl = %s;
                let task;
                try {
                    task = await taskSpace(%s);
                } catch (e) {
                    const __msg = String(e.message);
                    if (__msg.includes('has ended') || __msg.includes('not found')) {
                        throw new Error('任务空间已结束或被清理，请调用 create_canvas_space 重建');
                    }
                    throw new Error('专属空间不可用（可能被用户控制）：' + __msg);
                }
                const __tabs = await task.tabs();
                const __targetTab = __tabs.find(t => t.url && t.url.includes('canvas=' + __canvasId));
                let page;
                if (__targetTab) {
                    page = __targetTab.label
                        ? task.page(__targetTab.label)
                        : await task.adopt(__targetTab.page);
                } else {
                    const __existing = __tabs.find(t => t.label);
                    page = __existing ? task.page(__existing.label) : await task.newPage();
                    await page.goto(__canvasUrl);
                }
                console.log('__HB_BIND__ ' + JSON.stringify({ spaceId: task.spaceId, label: page.label }));
                await page.waitForSelector('.canvas-component, #canvas', { timeout: 15000 });
                
                // ===== 工具注入：画布操作 =====
                """.formatted(id, url, spaceName);
    }

    /**
     * 复用已绑定页面：直接恢复空间与页面，仅在 URL 偏离时导航回目标画布。
     */
    private String reusePrologue(String canvasId, Binding binding) {
        String id = quoteJs(canvasId);
        String url = quoteJs(uiUrl + "?canvas=" + canvasId);
        String spaceId = String.valueOf(binding.spaceId());
        String label = quoteJs(binding.pageLabel());
        return """
                // ===== 工具注入：恢复已绑定的画布页面 =====
                const __canvasId = %s;
                const __canvasUrl = %s;
                let task;
                let page;
                try {
                    task = await taskSpace(%s);
                    page = task.page(%s);
                } catch (e) {
                    const __msg = String(e.message);
                    if (__msg.includes('Control of this task space is with the user')) {
                        throw new Error('任务空间不可用（用户已接管浏览器操作）：' + __msg);
                    }
                    throw new Error('任务空间已结束或被清理，请调用 create_canvas_space 重建：' + __msg);
                }
                const __currentUrl = await page.url();
                if (!__currentUrl.includes('canvas=' + __canvasId)) {
                    await page.goto(__canvasUrl);
                }
                await page.waitForSelector('.canvas-component, #canvas', { timeout: 15000 });
                
                // ===== 工具注入：画布操作 =====
                """.formatted(id, url, spaceId, label);
    }

    private static String quoteJs(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }

    private static CompletableFuture<String> readAsync(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                return cap(new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip());
            } catch (IOException ex) {
                return "";
            }
        });
    }

    private static String safeGet(CompletableFuture<String> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "";
        } catch (TimeoutException | java.util.concurrent.ExecutionException ex) {
            return "";
        }
    }

    private static String cap(String text) {
        return text.length() <= MAX_STREAM_CHARS
                ? text
                : text.substring(0, MAX_STREAM_CHARS) + "\n...[truncated " + (text.length() - MAX_STREAM_CHARS) + " chars]";
    }

    private void pruneScripts() {
        try (var files = Files.list(scriptDir)) {
            List<Path> old = files
                    .filter(path -> path.getFileName().toString().startsWith("round-"))
                    .sorted(Comparator.comparingLong(CanvasToolSupport::lastModified).reversed())
                    .skip(KEEP_SCRIPTS)
                    .toList();
            for (Path path : old) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ex) {
            return 0;
        }
    }
}
