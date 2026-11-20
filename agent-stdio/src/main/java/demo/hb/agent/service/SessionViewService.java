package demo.hb.agent.service;

import demo.hb.agent.session.SessionRepository;
import io.agentscope.core.session.SessionEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 只读会话视图：聊天记录、轮次列表与单轮执行记录，数据全部来自已持久化的会话日志。 */
@Service
public final class SessionViewService {

    private static final int MAX_CACHED_SESSIONS = 8;
    private static final int MAX_TEXT = 2000;

    private final SessionRepository sessions;
    private final Map<String, Cache> caches = new LinkedHashMap<>(16, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cache> eldest) {
            return size() > MAX_CACHED_SESSIONS;
        }
    };

    /** 每个会话缓存已解析事件与投影结果，只补读新增事件。 */
    private static final class Cache {
        List<SessionEvent> events = List.of();
        long seq;
        Map<String, Object> history = Map.of("turns", List.of());
        long historySeq = -1;
        Map<String, Object> rounds = Map.of("rounds", List.of());
        long roundsSeq = -1;
    }

    public SessionViewService(SessionRepository sessions) {
        this.sessions = sessions;
    }

    public synchronized void delete(String canvasId) throws IOException {
        try {
            sessions.delete(canvasId);
        } finally {
            caches.remove(canvasId);
        }
    }

    /** 对话：每个 run 一组，用户消息 + 该 run 的最后一条助手消息，并保留记录时间。 */
    public synchronized Map<String, Object> history(String canvasId) {
        Cache cache = cache(canvasId);
        if (cache.historySeq != cache.seq) {
            cache.history = projectHistory(cache.events);
            cache.historySeq = cache.seq;
        }
        return cache.history;
    }

    /** 轨迹：一次用户请求为一轮，附步骤数、工具调用数与状态。 */
    public synchronized Map<String, Object> rounds(String canvasId) {
        Cache cache = cache(canvasId);
        if (cache.roundsSeq != cache.seq) {
            cache.rounds = projectRounds(cache.events);
            cache.roundsSeq = cache.seq;
        }
        return cache.rounds;
    }

    /** 单轮完整执行记录：按调用合并输入与输出，展示层字段全部由后端生成。 */
    public synchronized Map<String, Object> round(String canvasId, String runId) {
        Cache cache = cache(canvasId);
        var rows = new ArrayList<Map<String, Object>>();
        Map<String, Map<String, Object>> byCall = new LinkedHashMap<>();
        for (SessionEvent event : cache.events) {
            if (!Objects.equals(runId, event.executionRunId())) continue;
            var data = event.data();
            long at = event.occurredAt();
            if ("tool/result".equals(event.type())) {
                // 一条结果消息可能包含多个调用，按各自调用 ID 归回原记录。
                for (var result : toolResults(data)) {
                    Object id = result.get("id");
                    if (id == null) continue;
                    String key = "tool:" + id;
                    var row = byCall.get(key);
                    if (row == null) {
                        row = new LinkedHashMap<>();
                        row.put("kind", "tool");
                        row.put("status", "running");
                        row.put("startedAt", at);
                        row.put("events", 0);
                        byCall.put(key, row);
                        rows.add(row);
                    }
                    if (result.get("name") != null) row.putIfAbsent("title", "工具 · " + result.get("name"));
                    row.put("endedAt", at);
                    row.put("output", toolResultText(result));
                    row.put("status", toolResultState(result));
                    row.put("events", (int) row.get("events") + 1);
                }
                continue;
            }
            String key = callKey(data);
            if (key != null) {
                var row = byCall.get(key);
                if (row == null) {
                    row = new LinkedHashMap<>();
                    // 模型调用与工具调用使用不同的展示类型，不能由默认值推断。
                    row.put("kind", key.startsWith("model:") ? "model" : "tool");
                    row.put("status", "running");
                    row.put("startedAt", at);
                    row.put("events", 0);
                    byCall.put(key, row);
                    rows.add(row);
                }
                mergeCall(row, event.type(), data, at);
                row.put("events", (int) row.get("events") + 1);
                continue;
            }
            switch (event.type()) {
                case "message/user" -> rows.add(textRow("user", "用户", data, at));
                case "message/assistant" -> rows.add(textRow("assistant", "助手", data, at));
                case "run/start" -> rows.add(simple("system", "开始执行", "completed", at));
                case "run/end" -> {
                    // 运行结束只能证明调用停止等待，不能把未返回的调用标记为成功。
                    String status = resultStatus(data);
                    String openStatus = "completed".equals(status) ? "unconfirmed" : status;
                    for (var row : byCall.values()) {
                        if ("running".equals(row.get("status"))) row.put("status", openStatus);
                    }
                    rows.add(simple("system", "执行结束", status, at));
                }
                case "context/build" -> rows.add(contextBuildRow(data, at));
                case "request/prepared" -> rows.add(simple("system", "请求就绪", "completed", at));
                case "compaction/start" -> rows.add(simple("system", "上下文压缩", "running", at));
                case "compaction/end" -> rows.add(simple("system", "压缩完成", "completed", at));
                case "action/start" -> rows.add(simple("system", "执行动作", "running", at));
                case "action/end" -> rows.add(simple("system", "动作完成", "completed", at));
                case "task/changed" -> rows.add(simple("system", "任务变更", "completed", at));
                case "permission/changed" -> rows.add(simple("system", "权限变更", "completed", at));
                case "plan/changed" -> rows.add(simple("system", "计划变更", "completed", at));
                default -> { }
            }
        }
        return Map.of("runId", runId, "rows", rows);
    }

    /** 事件列表只补读新增部分；投影随 head 变化才重算。 */
    private Cache cache(String canvasId) {
        Cache cache = caches.get(canvasId);
        if (cache == null) {
            cache = new Cache();
            caches.put(canvasId, cache);
        }
        var increment = sessions.read(canvasId, cache.seq);
        if (increment.after() != cache.seq) {
            cache.events = List.of();
            cache.historySeq = -1;
            cache.roundsSeq = -1;
        } else if (increment.through() == cache.seq) {
            return cache;
        }
        var events = new ArrayList<SessionEvent>(cache.events);
        events.addAll(increment.events());
        cache.events = List.copyOf(events);
        cache.seq = increment.through();
        return cache;
    }

    private static void mergeCall(Map<String, Object> row, String type, Map<String, Object> data, long at) {
        switch (type) {
            case "model/dispatch" -> {
                row.put("kind", "model");
                row.put("title", "COMPACTION".equals(data.get("purpose")) ? "压缩模型调用" : "模型调用");
                row.putIfAbsent("input", data.get("model"));
            }
            case "model/chunk" -> {
                Object chunk = data.get("chunk");
                if (chunk instanceof Map<?, ?> block) {
                    String text = textOf(block.get("content"));
                    if (!text.isBlank()) row.put("text", cap(String.valueOf(row.getOrDefault("text", "")) + text));
                    String thinking = thinkingOf(block.get("content"));
                    if (!thinking.isBlank())
                        row.put("thinking", cap(String.valueOf(row.getOrDefault("thinking", "")) + thinking));
                    for (String tool : toolUsesOf(block.get("content"))) {
                        row.put("text", cap(String.valueOf(row.getOrDefault("text", ""))
                                + (row.containsKey("text") ? "\n" : "") + "→ 调用工具：" + tool));
                    }
                }
            }
            case "model/end" -> {
                row.put("endedAt", at);
                row.putIfAbsent("usage", data.get("usage"));
                row.put("status", "completed");
            }
            case "tool/requested" -> {
                row.put("kind", "tool");
                row.put("title", "工具 · " + toolName(data));
                row.put("input", toolArguments(data));
            }
            default -> { }
        }
    }

    private static Map<String, Object> projectRounds(List<SessionEvent> events) {
        var drafts = new ArrayList<RoundDraft>();
        RoundDraft current = null;
        for (SessionEvent event : events) {
            if (event.executionRunId() == null) continue;
            if (current == null || !current.runId.equals(event.executionRunId())) {
                current = new RoundDraft(event.executionRunId(), event.occurredAt());
                drafts.add(current);
            }
            var data = event.data();
            switch (event.type()) {
                case "input/received" -> {
                    if (current.user.isEmpty() && data.get("messages") instanceof List<?> messages
                            && !messages.isEmpty() && messages.get(0) instanceof Map<?, ?> first) {
                        current.user = userRequest(textOf(first.get("content")));
                    }
                }
                case "message/user" -> {
                    if (current.user.isEmpty() && data.get("message") instanceof Map<?, ?> message)
                        current.user = userRequest(textOf(message.get("content")));
                }
                case "step/start" -> current.steps++;
                case "tool/requested" -> current.tools++;
                case "run/end" -> {
                    current.endedAt = event.occurredAt();
                    current.status = resultStatus(data);
                }
                default -> { }
            }
        }
        var rounds = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < drafts.size(); i++) {
            RoundDraft draft = drafts.get(i);
            var row = new LinkedHashMap<String, Object>();
            row.put("index", i + 1);
            row.put("runId", draft.runId);
            row.put("user", draft.user.isBlank() ? "（未记录用户要求）" : draft.user);
            row.put("startedAt", draft.startedAt);
            if (draft.endedAt != null) row.put("endedAt", draft.endedAt);
            row.put("status", draft.status);
            row.put("steps", draft.steps);
            row.put("tools", draft.tools);
            rounds.add(row);
        }
        return Map.of("rounds", rounds);
    }

    private static final class RoundDraft {
        final String runId;
        final long startedAt;
        String user = "";
        Long endedAt;
        String status = "running";
        int steps;
        int tools;

        RoundDraft(String runId, long startedAt) {
            this.runId = runId;
            this.startedAt = startedAt;
        }
    }

    /** 对话投影：每轮包含用户消息与内联块（思考 / 工具 / 回答），全部来自事件。 */
    private static Map<String, Object> projectHistory(List<SessionEvent> events) {
        var turns = new ArrayList<Map<String, Object>>();
        Map<String, Object> current = null;
        Map<String, Object> lastBlock = null;
        Map<String, Map<String, Object>> tools = new LinkedHashMap<>();
        String runId = null;
        long head = 0;
        for (SessionEvent event : events) {
            head = Math.max(head, event.seq());
            if (event.executionRunId() != null && !event.executionRunId().equals(runId)) {
                if (current != null) turns.add(current);
                runId = event.executionRunId();
                current = new LinkedHashMap<>();
                current.put("runId", runId);
                current.put("status", "running");
                current.put("startedAt", event.occurredAt());
                current.put("blocks", new ArrayList<Map<String, Object>>());
                lastBlock = null;
                tools = new LinkedHashMap<>();
            }
            if (current == null) continue;
            var data = event.data();
            @SuppressWarnings("unchecked")
            var blocks = (List<Map<String, Object>>) current.get("blocks");
            switch (event.type()) {
                case "input/received" -> {
                    if (!current.containsKey("user") && data.get("messages") instanceof List<?> messages
                            && !messages.isEmpty() && messages.get(0) instanceof Map<?, ?> first) {
                        String text = userRequest(textOf(first.get("content")));
                        if (!text.isBlank()) {
                            current.put("user", text);
                            current.put("userAt", event.occurredAt());
                        }
                    }
                }
                case "message/user" -> {
                    if (!current.containsKey("user") && data.get("message") instanceof Map<?, ?> message) {
                        String text = userRequest(textOf(message.get("content")));
                        if (!text.isBlank()) {
                            current.put("user", text);
                            current.put("userAt", event.occurredAt());
                        }
                    }
                }
                case "model/chunk" -> {
                    Object chunk = data.get("chunk");
                    if (chunk instanceof Map<?, ?> block && block.get("content") instanceof List<?> content) {
                        for (Object value : content) {
                            if (!(value instanceof Map<?, ?> piece)) continue;
                            String type = String.valueOf(piece.get("type"));
                            if ("text".equals(type) && piece.get("text") != null) {
                                String text = String.valueOf(piece.get("text"));
                                if (!text.isBlank())
                                    lastBlock = appendText(blocks, lastBlock, "answer", event.occurredAt(), text);
                            } else if ("thinking".equals(type) && piece.get("thinking") != null) {
                                String text = String.valueOf(piece.get("thinking"));
                                if (!text.isBlank())
                                    lastBlock = appendText(blocks, lastBlock, "reasoning", event.occurredAt(), text);
                            }
                        }
                    }
                }
                case "tool/requested" -> {
                    String key = callKey(data);
                    if (key == null) break;
                    var row = new LinkedHashMap<String, Object>();
                    row.put("kind", "tool");
                    row.put("name", toolName(data));
                    row.put("status", "pending");
                    row.put("startedAt", event.occurredAt());
                    row.put("timestampMs", event.occurredAt());
                    row.put("input", toolArguments(data));
                    tools.put(key, row);
                    blocks.add(row);
                    lastBlock = null;
                }
                case "tool/dispatch" -> {
                    var row = callKey(data) == null ? null : tools.get(callKey(data));
                    if (row != null && !"completed".equals(row.get("status")) && !"failed".equals(row.get("status")))
                        row.put("status", "running");
                }
                case "tool/result" -> {
                    for (var result : toolResults(data)) {
                        Object id = result.get("id");
                        var row = id == null ? null : tools.get("tool:" + id);
                        if (row == null) continue;
                        row.put("status", toolResultState(result));
                        row.put("endedAt", event.occurredAt());
                        row.put("output", toolResultText(result));
                    }
                }
                case "model/end" -> {
                    // 模型结束但正式消息未落库：生成中的文本块进入收尾。
                    for (var block : blocks) {
                        if (("answer".equals(block.get("kind")) || "reasoning".equals(block.get("kind")))
                                && "generating".equals(block.get("status")))
                            block.put("status", "finishing");
                    }
                }
                case "message/assistant" -> {
                    // 正式消息已提交，生成中的文本块收尾，明细仍随块保留。
                    for (var block : blocks) {
                        if (("answer".equals(block.get("kind")) || "reasoning".equals(block.get("kind")))
                                && isActiveBlock(String.valueOf(block.get("status"))))
                            block.put("status", "completed");
                    }
                }
                case "run/end" -> {
                    String status = resultStatus(data);
                    current.put("status", status);
                    current.put("endedAt", event.occurredAt());
                    for (var block : blocks) {
                        String blockStatus = String.valueOf(block.get("status"));
                        if ("tool".equals(block.get("kind"))) {
                            if ("pending".equals(blockStatus) || "running".equals(blockStatus))
                                block.put("status", "completed".equals(status) ? "unconfirmed" : status);
                        } else if (isActiveBlock(blockStatus)) {
                            block.put("status", status);
                        }
                    }
                }
                default -> { }
            }
        }
        if (current != null) turns.add(current);
        return Map.of("head", head, "turns", turns);
    }

    private static boolean isActiveBlock(String status) {
        return List.of("running", "generating", "finishing", "queued", "starting").contains(status);
    }

    /** 连续同类文本块合并，避免每个 chunk 都生成一条记录。 */
    private static Map<String, Object> appendText(List<Map<String, Object>> blocks, Map<String, Object> last,
                                                  String kind, long at, String text) {
        if (last != null && kind.equals(last.get("kind"))) {
            last.put("content", cap(String.valueOf(last.getOrDefault("content", "")) + text));
            return last;
        }
        var row = new LinkedHashMap<String, Object>();
        row.put("kind", kind);
        row.put("content", cap(text));
        row.put("status", "generating");
        row.put("timestampMs", at);
        blocks.add(row);
        return row;
    }

    /** 只保留用户原始要求，去掉任务模板前缀、压缩摘要注入与画布基线。 */
    private static String userRequest(String text) {
        String marker = "用户要求：";
        int at = text.lastIndexOf(marker);
        if (at >= 0) text = text.substring(at + marker.length());
        at = text.indexOf("当前画布 JSON（参考基线）：");
        if (at >= 0) text = text.substring(0, at);
        String request = text.strip();
        return request.isBlank() ? "" : request;
    }

    private static String callKey(Map<String, Object> data) {
        if (data.get("modelCallId") instanceof String id) return "model:" + id;
        if (data.get("toolCallId") instanceof String id) return "tool:" + id;
        if (data.get("call") instanceof Map<?, ?> call && call.get("id") instanceof String id) return "tool:" + id;
        return null;
    }

    private static String toolName(Map<String, Object> data) {
        if (data.get("call") instanceof Map<?, ?> call && call.get("name") != null)
            return String.valueOf(call.get("name"));
        return data.get("name") == null ? "调用" : String.valueOf(data.get("name"));
    }

    private static String toolArguments(Map<String, Object> data) {
        Object arguments = null;
        if (data.get("call") instanceof Map<?, ?> call) {
            arguments = call.get("input") != null ? call.get("input") : call.get("arguments");
        }
        if (arguments == null) arguments = data.get("arguments");
        return arguments == null ? "" : cap(String.valueOf(arguments));
    }

    /** 一条工具结果消息可能包含多个 tool_result，按块拆分后各自归并。 */
    private static List<Map<?, ?>> toolResults(Map<String, Object> data) {
        if (!(data.get("message") instanceof Map<?, ?> message)
                || !(message.get("content") instanceof List<?> content)) return List.of();
        var results = new ArrayList<Map<?, ?>>();
        for (Object value : content) {
            if (value instanceof Map<?, ?> block && "tool_result".equals(block.get("type"))) results.add(block);
        }
        return results;
    }

    private static String toolResultText(Map<?, ?> result) {
        if (!(result.get("output") instanceof List<?> output)) return "";
        var text = new StringBuilder();
        for (Object value : output) {
            if (value instanceof Map<?, ?> item && item.get("text") != null) text.append(item.get("text"));
        }
        return cap(text.toString());
    }

    private static String toolResultState(Map<?, ?> result) {
        String state = String.valueOf(result.get("state")).toLowerCase();
        return List.of("error", "failed").contains(state) ? "failed" : "completed";
    }

    private static String resultStatus(Map<String, Object> data) {
        String status = String.valueOf(data.getOrDefault("status", "")).toLowerCase();
        return List.of("completed", "success", "done").contains(status) ? "completed" : "failed";
    }

    private static Map<String, Object> simple(String kind, String title, String status, long at) {
        var row = new LinkedHashMap<String, Object>();
        row.put("kind", kind);
        row.put("title", title);
        row.put("status", status);
        row.put("startedAt", at);
        return row;
    }

    /** 构建上下文事件带模型的预算信息，拼进标题避免详情为空。 */
    private static Map<String, Object> contextBuildRow(Map<String, Object> data, long at) {
        var title = new StringBuilder("构建上下文");
        var event = data.get("event") instanceof Map<?, ?> value ? value : null;
        var manifest = event != null && event.get("value") instanceof Map<?, ?> value
                && value.get("context_manifest") instanceof Map<?, ?> m ? m : null;
        if (manifest != null) {
            if (manifest.get("purpose") != null) title.append(" · ").append(manifest.get("purpose"));
            if (manifest.get("model") != null) title.append(" · ").append(manifest.get("model"));
            Object estimated = manifest.get("estimatedInputTokens");
            Object limit = manifest.get("inputLimit");
            if (estimated != null) {
                title.append(" · ").append(estimated);
                if (limit != null) title.append("/").append(limit);
                title.append(" tokens");
            }
        }
        return simple("system", title.toString(), "completed", at);
    }

    private static Map<String, Object> textRow(String kind, String title, Map<String, Object> data, long at) {
        String text = data.get("message") instanceof Map<?, ?> message ? textOf(message.get("content")) : "";
        // 轨迹中的用户消息同样只保留原始要求，避免展示任务模板与画布基线。
        if ("user".equals(kind)) text = userRequest(text);
        var row = simple(kind, title, "completed", at);
        if (!text.isBlank()) row.put("text", cap(text));
        return row;
    }

    private static String cap(String text) {
        return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "…" : text;
    }

    private static String textOf(Object content) {
        if (!(content instanceof List<?> blocks)) return "";
        var text = new StringBuilder();
        for (Object block : blocks) {
            if (block instanceof Map<?, ?> b && "text".equals(b.get("type")) && b.get("text") != null) {
                text.append(b.get("text"));
            }
        }
        return text.toString();
    }

    private static String thinkingOf(Object content) {
        if (!(content instanceof List<?> blocks)) return "";
        var text = new StringBuilder();
        for (Object block : blocks) {
            if (block instanceof Map<?, ?> b && "thinking".equals(b.get("type")) && b.get("thinking") != null) {
                text.append(b.get("thinking"));
            }
        }
        return text.toString();
    }

    /** 抽取模型这一轮请求的工具调用名，作为无文本输出时的明细。 */
    private static List<String> toolUsesOf(Object content) {
        if (!(content instanceof List<?> blocks)) return List.of();
        var names = new ArrayList<String>();
        for (Object block : blocks) {
            if (block instanceof Map<?, ?> b && "tool_use".equals(b.get("type")) && b.get("name") != null) {
                names.add(String.valueOf(b.get("name")));
            }
        }
        return names;
    }
}

