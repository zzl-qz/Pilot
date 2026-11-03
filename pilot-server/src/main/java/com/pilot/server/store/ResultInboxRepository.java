package com.pilot.server.store;

import com.pilot.core.event.EventEnvelope;
import com.pilot.core.session.SessionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

/** 结束事件是事实来源；这里只持久化用户的阅读/处理标记。 */
@Component
public class ResultInboxRepository {
    private final SqliteStore store;
    private final EventCodec codec;
    private final SessionService sessions;
    private final Map<String, Receipt> memory = new HashMap<>();
    public record Receipt(String revision, boolean seen, boolean handled) { }
    @org.springframework.beans.factory.annotation.Autowired
    public ResultInboxRepository(ObjectProvider<SqliteStore> store, EventCodec codec, SessionService sessions) {
        this(store.getIfAvailable(), codec, sessions);
    }
    public ResultInboxRepository(SqliteStore store, EventCodec codec, SessionService sessions) {
        this.store = store; this.codec = codec; this.sessions = sessions;
    }
    public List<EventEnvelope> endings(int limit) { return endings(limit, Instant.EPOCH); }
    public List<EventEnvelope> endings(int limit, Instant since) {
        if (store == null) {
            Map<String, EventEnvelope> latest = new HashMap<>();
            sessions.list().stream().filter(s -> !s.isSide())
                    .sorted(Comparator.comparing(com.pilot.core.session.Session::createdAt).thenComparing(com.pilot.core.session.Session::id))
                    .forEach(s -> {
                        Map<String, EventEnvelope> byRun = new HashMap<>();
                        sessions.events(s.id()).stream()
                                .filter(e -> Set.of("run.completed", "run.failed", "run.cancelled").contains(e.type()))
                                .forEach(e -> byRun.put(e.runId(), e));
                        byRun.forEach(latest::putIfAbsent);
                    });
            return latest.values().stream().filter(e -> !e.timestamp().isBefore(since)).filter(e -> !(e.payload() instanceof com.pilot.core.event.RunCancelled)
                            && !(e.payload() instanceof com.pilot.core.event.RunFailed f && Set.of("WAITING", "PAUSED").contains(f.code()))).sorted(Comparator.comparing(EventEnvelope::timestamp).reversed())
                    .limit(limit).toList();
        }
        return JdbcSupport.query(store, """
                WITH candidates AS (
                    SELECT e.*, ROW_NUMBER() OVER (PARTITION BY e.run_id
                        ORDER BY s.created_at, s.id, e.sequence DESC) AS result_rank
                    FROM events e JOIN sessions s ON s.id = e.session_id
                    WHERE (s.parent_session_id IS NULL OR s.parent_session_id = '')
                    AND e.type IN ('run.completed','run.failed','run.cancelled')
                )
                SELECT * FROM candidates WHERE result_rank=1 AND type != 'run.cancelled'
                AND (type != 'run.failed' OR COALESCE(json_extract(payload, '$.code'), '') NOT IN ('WAITING','PAUSED'))
                AND julianday(created_at)>=julianday(?)
                ORDER BY created_at DESC, session_id, sequence DESC LIMIT ?
                """, q -> { q.setString(1,since.toString()); q.setInt(2, limit); }, row -> new EventEnvelope(row.getLong("sequence"),
                Instant.parse(row.getString("created_at")), row.getString("type"), row.getString("session_id"),
                row.getString("run_id"), codec.decode(row.getString("type"), row.getString("payload"))), "读取任务结果失败");
    }
    /** 列表轮询批量读取，避免每条结果打开一次 SQLite 连接。 */
    public synchronized Map<String, Receipt> receipts(List<String> requested) {
        List<String> ids = requested.stream().filter(Objects::nonNull).distinct().toList();
        Map<String, Receipt> result = new HashMap<>();
        if (store == null) { ids.forEach(id -> { if (memory.containsKey(id)) result.put(id, memory.get(id)); }); return result; }
        for (int from = 0; from < ids.size(); from += 400) {
            var chunk = ids.subList(from, Math.min(ids.size(), from + 400));
            var rows = JdbcSupport.query(store, "SELECT * FROM result_receipts WHERE id IN ("
                    + String.join(",", Collections.nCopies(chunk.size(), "?")) + ")", q -> {
                for (int i = 0; i < chunk.size(); i++) q.setString(i + 1, chunk.get(i));
            }, row -> Map.entry(row.getString("id"), new Receipt(row.getString("revision"),
                    row.getInt("seen") != 0, row.getInt("handled") != 0)), "读取收件状态失败");
            rows.forEach(row -> result.put(row.getKey(), row.getValue()));
        }
        return result;
    }
    public synchronized Receipt receipt(String id, String revision) {
        Receipt r = store == null ? memory.get(id) : JdbcSupport.queryOne(store,
                "SELECT * FROM result_receipts WHERE id = ?", q -> q.setString(1, id),
                row -> new Receipt(row.getString("revision"), row.getInt("seen") != 0, row.getInt("handled") != 0), "读取收件状态失败").orElse(null);
        return r != null && r.revision.equals(revision) ? r : new Receipt(revision, false, false);
    }
    public synchronized Receipt mark(String id, String revision, String action) {
        var old = receipt(id, revision);
        var next = switch (action) {
            case "read" -> new Receipt(revision, true, old.handled);
            case "unread" -> new Receipt(revision, false, old.handled);
            case "handle" -> new Receipt(revision, true, true);
            case "reopen" -> new Receipt(revision, true, false);
            default -> throw new IllegalArgumentException("不支持的收件操作");
        };
        if (store == null) memory.put(id, next);
        else JdbcSupport.update(store, "INSERT INTO result_receipts(id, revision, seen, handled) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT(id) DO UPDATE SET revision=excluded.revision, seen=excluded.seen, handled=excluded.handled", q -> {
            q.setString(1, id); q.setString(2, revision); q.setInt(3, next.seen ? 1 : 0); q.setInt(4, next.handled ? 1 : 0);
        }, "保存收件状态失败");
        return next;
    }
}
