package com.pilot.core.runtime.bootstrap;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** 下载缓存默认随项目保存；显式配置的工具缓存位置优先，不改用户全局设置。 */
public final class DependencyCaches {
    private DependencyCaches() {
    }

    public static Map<String, String> environment(Path project, Map<String, String> inherited) {
        Map<String, String> result = new HashMap<>(inherited);
        Path root = project.toAbsolutePath().normalize();
        String configured = value(inherited, "PILOT_DEPENDENCY_CACHE");
        Path cache = configured.isBlank() ? root.resolve(".pilot/cache") : root.resolve(configured).normalize();
        putDefault(result, "npm_config_cache", cache.resolve("npm"));
        putDefault(result, "electron_config_cache", cache.resolve("electron"));
        putDefault(result, "PILOT_MAVEN_REPOSITORY", cache.resolve("maven"));
        return result;
    }

    private static void putDefault(Map<String, String> env, String key, Path directory) {
        if (value(env, key).isBlank()) {
            env.keySet().removeIf(existing -> existing.equalsIgnoreCase(key));
            env.put(key, directory.toString());
        }
    }

    private static String value(Map<String, String> env, String key) {
        return env.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(key)).map(Map.Entry::getValue)
            .findFirst().orElse("");
    }
}
