package com.pilot.server.store;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 进程内设置存储，用于 {@code pilot.store=memory} 与测试。
 *
 * <p>保存时整表重建再一次性替换引用：读方要么看到旧快照，要么看到新快照，
 * 不会读到「端点已改、密钥还没改」这种中间状态。
 */
public final class InMemorySettingsStore implements SettingsStore {
    private volatile Map<String, String> values = Map.of();

    @Override
    public Optional<String> find(String key) {
        return Optional.ofNullable(values.get(key));
    }

    @Override
    public synchronized void save(String key, String value) {
        saveAll(Map.of(key, value));
    }

    @Override
    public synchronized void saveAll(Map<String, String> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        var next = new HashMap<>(values);
        next.putAll(updates);
        // Map.copyOf 拒绝 null 键值：属于调用方错误，直接抛出且不动原快照
        values = Map.copyOf(next);
    }
}
