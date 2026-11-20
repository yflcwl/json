package demo.hb.agent.session;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.JournalSessionLog;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionLogStore;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.harness.agent.session.LocalSessionStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SessionRepository {

    private static final String USER_ID = "sketch-workspace";
    private static final String AGENT_ID = "html-agent";
    private final Path journalRoot;
    private final CachedSessionLogStore logs;
    private final JsonFileAgentStateStore states;

    public SessionRepository(Path workspace) {
        journalRoot = workspace.resolve("session-log").toAbsolutePath().normalize();
        var storage = new LocalSessionStorage(journalRoot);
        logs = new CachedSessionLogStore(
                (key, context) -> new JournalSessionLog(storage, key.storagePath()));
        states = new JsonFileAgentStateStore(workspace.resolve("state"));
    }

    public SessionLogStore logs() {
        return logs;
    }

    public AgentStateStore states() {
        return states;
    }

    public Events read(String canvasId, long after) {
        var key = new SessionKey(USER_ID, AGENT_ID, canvasId);
        var log = logs.open(key, RuntimeContext.empty());
        long through = log.head().seq();
        long start = through < after ? 0 : after;
        if (start == through) return new Events(start, through, List.of());
        var events = new ArrayList<SessionEvent>();
        for (SessionEvent event : log.scan(start, through)) events.add(event);
        return new Events(start, through, List.copyOf(events));
    }

    public record Events(long after, long through, List<SessionEvent> events) {}

    /** 框架无会话删除入口，存储布局兼容集中在这里处理。 */
    public void delete(String canvasId) throws IOException {
        var key = new SessionKey(USER_ID, AGENT_ID, canvasId);
        try {
            Path database = journalRoot.resolve("journal.sqlite3");
            if (Files.exists(database)) {
                String prefix = key.storagePath() + "/";
                try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                     var statement = connection.prepareStatement(
                             "DELETE FROM session_objects WHERE substr(path, 1, length(?)) = ?")) {
                    statement.setString(1, prefix);
                    statement.setString(2, prefix);
                    statement.executeUpdate();
                } catch (java.sql.SQLException ex) {
                    throw new IOException("删除会话日志失败", ex);
                }
            } else {
                Path directory = journalRoot.resolve(key.storagePath()).normalize();
                if (!directory.startsWith(journalRoot) || directory.equals(journalRoot)) {
                    throw new IOException("会话目录超出存储范围");
                }
                if (Files.exists(directory)) {
                    try (var walk = Files.walk(directory)) {
                        for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) {
                            Files.deleteIfExists(file);
                        }
                    }
                }
            }
            states.delete(USER_ID, canvasId);
        } finally {
            logs.invalidate(key);
        }
    }
}
