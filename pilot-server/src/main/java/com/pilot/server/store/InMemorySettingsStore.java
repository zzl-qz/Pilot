package com.pilot.server.store;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 进程内设置存储，用于 {@code pilot.store=memory} 与测试。 */
public final class InMemorySettingsStore implements SettingsStore {
    private final ConcurrentMap<String, String> values = new ConcurrentHashMap<>();

    @Override
    public Optional<String> find(String key) {
        return Optional.ofNullable(values.get(key));
    }

    @Override
    public void save(String key, String value) {
        values.put(key, value);
    }
}
