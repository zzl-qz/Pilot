package com.pilot.server.store;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.pilot.server.schedule.ScheduledTask;

import tools.jackson.databind.ObjectMapper;

/** 复用 SQLite；版本 CAS 让到期领取、用户暂停和取消不能互相覆盖。内存模式仅用于测试。 */
@Component
public class ScheduleRepository {
    private final SqliteStore store;
    private final ObjectMapper mapper;
    private final Map<String, ScheduledTask> memory = new LinkedHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public ScheduleRepository(ObjectProvider<SqliteStore> store, ObjectMapper mapper) {
        this(store.getIfAvailable(), mapper);
    }

    public ScheduleRepository(SqliteStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    public synchronized List<ScheduledTask> list() {
        return store == null ? List.copyOf(memory.values())
            : JdbcSupport.query(store, "SELECT payload FROM scheduled_tasks ORDER BY rowid DESC", s -> {
            }, r -> mapper.readValue(r.getString(1), ScheduledTask.class), "读取定时任务失败");
    }

    public synchronized Optional<ScheduledTask> find(String id) {
        return store == null ? Optional.ofNullable(memory.get(id))
            : JdbcSupport.queryOne(store, "SELECT payload FROM scheduled_tasks WHERE id = ?", s -> s.setString(1, id),
                r -> mapper.readValue(r.getString(1), ScheduledTask.class), "读取定时任务失败");
    }

    public synchronized void insert(ScheduledTask task) {
        if (store == null) {
            if (memory.putIfAbsent(task.id(), task) != null) {
                throw new IllegalStateException("计划已存在");
            }
            return;
        }
        JdbcSupport.update(store, "INSERT INTO scheduled_tasks(id, version, payload) VALUES (?, ?, ?)", s -> {
            s.setString(1, task.id());
            s.setLong(2, task.version());
            s.setString(3, mapper.writeValueAsString(task));
        }, "保存定时任务失败");
    }

    public synchronized boolean replace(ScheduledTask old, ScheduledTask next) {
        if (next.version() != old.version() + 1) {
            throw new IllegalArgumentException("计划版本必须递增");
        }
        if (store == null) {
            return memory.replace(old.id(), old, next);
        }
        return JdbcSupport.update(store,
            "UPDATE scheduled_tasks SET version = ?, payload = ? WHERE id = ? AND version = ?", s -> {
                s.setLong(1, next.version());
                s.setString(2, mapper.writeValueAsString(next));
                s.setString(3, old.id());
                s.setLong(4, old.version());
            }, "更新定时任务失败") == 1;
    }
}
