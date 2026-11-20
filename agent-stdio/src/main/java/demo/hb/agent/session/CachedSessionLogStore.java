package demo.hb.agent.session;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionLogException;
import io.agentscope.core.session.SessionLogStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 进程内缓存已提交事件的 SessionLogStore。
 *
 * <p>框架每次运行都会从 journal 全量扫描事件（投影恢复、turn 校验、transcript），大日志下这
 * 段 IO 会拖慢每轮执行。这里按会话缓存提交前缀，扫描直接走内存；head 前进时一次性补齐，
 * commit 直接追加到缓存。缓存只做读加速，写入仍同步落到 delegate。
 */
public final class CachedSessionLogStore implements SessionLogStore {

    private static final Logger log = LoggerFactory.getLogger(CachedSessionLogStore.class);

    /** 单会话缓存上限；超过后放弃缓存、退化为直读，避免大日志吃掉堆内存。 */
    private static final long DEFAULT_MAX_BYTES_PER_SESSION =
            Math.max(64L * 1024 * 1024, Math.min(256L * 1024 * 1024,
                    Runtime.getRuntime().maxMemory() / 8));

    /** 最多缓存的会话数，按最近访问淘汰。 */
    private static final int MAX_CACHED_SESSIONS = 4;

    private final SessionLogStore delegate;
    private final long maxBytesPerSession;
    private final Map<String, CachedLog> logs =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedLog> eldest) {
                    return size() > MAX_CACHED_SESSIONS;
                }
            };

    public CachedSessionLogStore(SessionLogStore delegate) {
        this(delegate, DEFAULT_MAX_BYTES_PER_SESSION);
    }

    public CachedSessionLogStore(SessionLogStore delegate, long maxBytesPerSession) {
        this.delegate = delegate;
        this.maxBytesPerSession = maxBytesPerSession;
    }

    @Override
    public SessionLog open(SessionKey key, RuntimeContext context) {
        String path = key.storagePath();
        synchronized (logs) {
            return logs.computeIfAbsent(
                    path, ignored -> new CachedLog(delegate.open(key, context), path));
        }
    }

    @Override
    public List<SessionKey> list(RuntimeContext context) {
        return delegate.list(context);
    }

    public void invalidate(SessionKey key) {
        synchronized (logs) {
            logs.remove(key.storagePath());
        }
    }

    /** 单个会话的缓存视图：事件序号连续，按 head 增量补读。 */
    private final class CachedLog implements SessionLog {

        private final SessionLog delegate;
        private final String path;
        private final List<SessionEvent> events = new ArrayList<>();
        private long bytes;
        private boolean disabled;

        private CachedLog(SessionLog delegate, String path) {
            this.delegate = delegate;
            this.path = path;
        }

        @Override
        public Head head() {
            return delegate.head();
        }

        @Override
        public Writer acquire(String owner, Duration lease) {
            return delegate.acquire(owner, lease);
        }

        @Override
        public void renew(Writer writer, Duration lease) {
            delegate.renew(writer, lease);
        }

        @Override
        public void release(Writer writer) {
            delegate.release(writer);
        }

        @Override
        public Head commit(
                Writer writer, String batchId, long expectedSeq, List<SessionEvent> committed) {
            Head head = delegate.commit(writer, batchId, expectedSeq, committed);
            synchronized (this) {
                if (!disabled) appendCommitted(committed);
            }
            return head;
        }

        @Override
        public Iterable<SessionEvent> scan(long after, long through) {
            synchronized (this) {
                refresh();
                if (disabled) {
                    var all = new ArrayList<SessionEvent>();
                    for (SessionEvent event : delegate.scan(after, through)) all.add(event);
                    return List.copyOf(all);
                }
                int from = (int) Math.min(after, events.size());
                int to = (int) Math.min(through, events.size());
                return List.copyOf(events.subList(from, to));
            }
        }

        @Override
        public List<SessionEvent> readAfter(long after, int limit) {
            if (limit <= 0) return List.of();
            synchronized (this) {
                refresh();
                if (disabled) return delegate.readAfter(after, limit);
                int from = (int) Math.min(after, events.size());
                int to = (int) Math.min((long) from + limit, events.size());
                return List.copyOf(events.subList(from, to));
            }
        }

        /** 一次性补齐到当前 head：delegate.scan 每次都会重建提交链，逐页读取代价极高。 */
        private void refresh() {
            if (disabled) return;
            long headSeq = delegate.head().seq();
            if (headSeq < events.size()) {
                // 会话被重置，缓存前缀失效。
                events.clear();
                bytes = 0;
            }
            if (events.size() >= headSeq) return;
            long startedAt = System.nanoTime();
            var fetched = new ArrayList<SessionEvent>();
            long next = events.size() + 1L;
            long fetchedBytes = bytes;
            for (SessionEvent event : delegate.scan(events.size(), headSeq)) {
                if (event.seq() != next) {
                    throw new SessionLogException("Non-contiguous journal prefix: " + path);
                }
                fetched.add(event);
                fetchedBytes += event.payloadJson().length() + 96L;
                next++;
            }
            if (fetchedBytes > maxBytesPerSession) {
                events.clear();
                bytes = 0;
                disabled = true;
                log.info("session journal cache disabled (size limit): {}", path);
                return;
            }
            events.addAll(fetched);
            bytes = fetchedBytes;
            if (!fetched.isEmpty()) {
                log.debug(
                        "session journal cached: {}, +{} events, {} bytes, took {} ms",
                        path,
                        fetched.size(),
                        bytes,
                        (System.nanoTime() - startedAt) / 1_000_000);
            }
        }

        private void appendCommitted(List<SessionEvent> committed) {
            for (SessionEvent event : committed) {
                if (event.seq() <= events.size()) continue;
                if (event.seq() != events.size() + 1L) {
                    // 出现缺口说明缓存前缀不可信，丢弃后由下次扫描重新读取。
                    events.clear();
                    bytes = 0;
                    return;
                }
                events.add(event);
                bytes += event.payloadJson().length() + 96L;
            }
            if (bytes > maxBytesPerSession) {
                events.clear();
                bytes = 0;
                disabled = true;
                log.info("session journal cache disabled (size limit): {}", path);
            }
        }
    }
}
