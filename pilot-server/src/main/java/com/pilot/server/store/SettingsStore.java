package com.pilot.server.store;

import java.util.Optional;

/** 应用级设置存储：键值对形式，进程重启后需要保留的用户选择。 */
public interface SettingsStore {
    Optional<String> find(String key);

    void save(String key, String value);
}
