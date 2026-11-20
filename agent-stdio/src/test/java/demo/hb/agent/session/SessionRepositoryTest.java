package demo.hb.agent.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import demo.hb.agent.service.SessionViewService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.state.AgentState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionRepositoryTest {

    @TempDir Path directory;
    private SessionRepository sessions;
    private String id;

    @BeforeEach
    void setUp() {
        sessions = new SessionRepository(directory);
        id = UUID.randomUUID().toString();
    }

    @Test
    void readsOnlyNewEventsAndRestartsAfterReset() throws Exception {
        append(id, "run/start", "{}");
        append(id, "run/end", "{\"status\":\"completed\"}");
        var initial = sessions.read(id, 0);
        assertEquals(0, initial.after());
        assertEquals(2, initial.through());
        assertEquals(List.of("run/start", "run/end"), initial.events().stream().map(SessionEvent::type).toList());
        append(id, "step/start", "{}");
        var increment = sessions.read(id, initial.through());
        assertEquals(2, increment.after());
        assertEquals(3, increment.through());
        assertEquals(List.of("step/start"), increment.events().stream().map(SessionEvent::type).toList());
        assertTrue(sessions.read(id, 3).events().isEmpty());

        sessions.delete(id);
        append(id, "run/start", "{\"new\":true}");
        var reset = sessions.read(id, 3);
        assertEquals(0, reset.after());
        assertEquals(1, reset.through());
        assertEquals(Boolean.TRUE, reset.events().get(0).data().get("new"));
    }

    @Test
    void deletesOnlySelectedSessionAndInvalidatesJournalAndViewCaches() throws Exception {
        String other = UUID.randomUUID().toString();
        append(id, "run/start", "{}");
        append(other, "run/start", "{}");
        sessions.states().save("sketch-workspace", id, "agent_state", AgentState.builder().sessionId(id).build());
        sessions.states().save("sketch-workspace", other, "agent_state", AgentState.builder().sessionId(other).build());
        var view = new SessionViewService(sessions);
        assertEquals(1, ((List<?>) view.rounds(id).get("rounds")).size());
        SessionLog old = sessions.logs().open(key(id), RuntimeContext.empty());
        assertEquals(1, old.readAfter(0, 10).size());

        view.delete(id);
        assertTrue(sessions.read(id, 0).events().isEmpty());
        assertTrue(((List<?>) view.rounds(id).get("rounds")).isEmpty());
        assertFalse(sessions.states().exists("sketch-workspace", id));
        assertTrue(sessions.states().exists("sketch-workspace", other));
        assertEquals(1, sessions.read(other, 0).events().size());
        assertNotSame(old, sessions.logs().open(key(id), RuntimeContext.empty()));

        append(id, "run/start", "{\"new\":true}");
        assertEquals(Boolean.TRUE, sessions.read(id, 0).events().get(0).data().get("new"));
        assertEquals(1, ((List<?>) view.rounds(id).get("rounds")).size());
        view.delete(id);
        view.delete(id);
    }

    @Test
    void deletesDirectoryLayoutWithoutTouchingOtherSessions() throws Exception {
        String other = UUID.randomUUID().toString();
        Path selected = directory.resolve("session-log").resolve(key(id).storagePath());
        Path retained = directory.resolve("session-log").resolve(key(other).storagePath());
        Files.createDirectories(selected.resolve("commits"));
        Files.createDirectories(retained);
        Files.writeString(selected.resolve("commits/old.json"), "fixture");
        Files.writeString(retained.resolve("keep.json"), "keep");
        sessions.delete(id);
        assertFalse(Files.exists(selected));
        assertEquals("keep", Files.readString(retained.resolve("keep.json")));
    }

    @Test
    void invalidatesCachesWhenJournalDeletionFails() throws Exception {
        append(id, "run/start", "{}");
        var view = new SessionViewService(sessions);
        assertEquals(1, ((List<?>) view.rounds(id).get("rounds")).size());
        SessionLog old = sessions.logs().open(key(id), RuntimeContext.empty());
        Path db = directory.resolve("session-log/journal.sqlite3");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(db), "SQLite journal required");
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE session_objects");
        }
        assertThrows(IOException.class, () -> view.delete(id));
        assertNotSame(old, sessions.logs().open(key(id), RuntimeContext.empty()));
        assertTrue(((List<?>) view.rounds(id).get("rounds")).isEmpty());
    }

    private void append(String canvasId, String type, String payload) {
        var log = sessions.logs().open(key(canvasId), RuntimeContext.empty());
        var writer = log.acquire(UUID.randomUUID().toString(), Duration.ofSeconds(30));
        try {
            long seq = log.head().seq();
            var event = new SessionEvent(1, UUID.randomUUID().toString(), seq + 1,
                    System.currentTimeMillis(), type, "run-" + canvasId, "turn", true, payload);
            log.commit(writer, UUID.randomUUID().toString(), seq, List.of(event));
        } finally {
            log.release(writer);
        }
    }

    private static SessionKey key(String canvasId) {
        return new SessionKey("sketch-workspace", "html-agent", canvasId);
    }
}
