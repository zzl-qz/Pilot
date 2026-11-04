package com.pilot.server.store;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 自动化配置、模板与投递记录共用版本化存储；更新必须比较版本。 */
@Component
public class AutomationStore {
    public record Document(String id, long version, String payload) {
    }

    private final SqliteStore store;
    private final Map<String, Map<String, Document>> memory = new HashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public AutomationStore(ObjectProvider<SqliteStore> provider) {
        this(provider.getIfAvailable());
    }

    public AutomationStore(SqliteStore store) {
        this.store = store;
    }

    public synchronized List<Document> list(String kind) {
        if (store == null) {
            return List.copyOf(memory.getOrDefault(kind, Map.of()).values());
        }
        return JdbcSupport.query(store, "SELECT * FROM automation_documents WHERE kind=? ORDER BY id",
            q -> q.setString(1, kind),
            r -> new Document(r.getString("id"), r.getLong("version"), r.getString("payload")), "读取自动化记录失败");
    }

    public synchronized List<Document> deliveryCandidates() {
        if (store == null) {
            return list("delivery");
        }
        return JdbcSupport.query(store,
            "SELECT * FROM automation_documents WHERE kind='delivery' AND json_extract(payload,'$.state') IN ('PENDING','RETRY','SENDING') ORDER BY rowid",
            q -> {
            }, r -> new Document(r.getString("id"), r.getLong("version"), r.getString("payload")), "读取待投递通知失败");
    }

    public synchronized List<Document> latest(String kind, int limit) {
        if (store == null) {
            var rows = new ArrayList<>(list(kind));
            Collections.reverse(rows);
            return rows.stream().limit(limit).toList();
        }
        return JdbcSupport.query(store, "SELECT * FROM automation_documents WHERE kind=? ORDER BY rowid DESC LIMIT ?",
            q -> {
                q.setString(1, kind);
                q.setInt(2, limit);
            }, r -> new Document(r.getString("id"), r.getLong("version"), r.getString("payload")), "读取最新记录失败");
    }

    public synchronized Optional<Document> get(String kind, String id) {
        if (store == null) {
            return Optional.ofNullable(memory.getOrDefault(kind, Map.of()).get(id));
        }
        return JdbcSupport.queryOne(store, "SELECT * FROM automation_documents WHERE kind=? AND id=?", q -> {
            q.setString(1, kind);
            q.setString(2, id);
        }, r -> new Document(r.getString("id"), r.getLong("version"), r.getString("payload")), "读取自动化记录失败");
    }

    public synchronized boolean put(String kind, String id, long expected, String payload) {
        Document next = new Document(id, expected + 1, payload);
        if (store == null) {
            var bucket = memory.computeIfAbsent(kind, key -> new LinkedHashMap<>());
            var old = bucket.get(id);
            if (expected == 0 ? old != null : old == null || old.version != expected) {
                return false;
            }
            bucket.put(id, next);
            return true;
        }
        if (expected == 0) {
            return JdbcSupport.update(store,
                "INSERT OR IGNORE INTO automation_documents(kind,id,version,payload) VALUES (?,?,1,?)", q -> {
                    q.setString(1, kind);
                    q.setString(2, id);
                    q.setString(3, payload);
                }, "保存自动化记录失败") == 1;
        }
        return JdbcSupport.update(store,
            "UPDATE automation_documents SET version=?,payload=? WHERE kind=? AND id=? AND version=?", q -> {
                q.setLong(1, expected + 1);
                q.setString(2, payload);
                q.setString(3, kind);
                q.setString(4, id);
                q.setLong(5, expected);
            }, "更新自动化记录失败") == 1;
    }

    public synchronized boolean delete(String kind, String id, long version) {
        if (store == null) {
            var old = get(kind, id).orElse(null);
            return old != null && old.version == version && memory.get(kind).remove(id, old);
        }
        return JdbcSupport.update(store, "DELETE FROM automation_documents WHERE kind=? AND id=? AND version=?", q -> {
            q.setString(1, kind);
            q.setString(2, id);
            q.setLong(3, version);
        }, "删除自动化记录失败") == 1;
    }
}
