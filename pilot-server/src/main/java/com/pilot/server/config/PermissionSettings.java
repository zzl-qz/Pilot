package com.pilot.server.config;

import com.pilot.core.permission.PermissionMode;
import com.pilot.server.store.SettingsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 当前生效的权限档位。
 *
 * <p>优先级：库中保存的用户选择 &gt; 配置文件 {@code pilot.agent.permission-mode}。
 * 通过 {@code PUT /api/settings/permission-mode} 切换后立即生效并落盘，重启后仍然保留；
 * 想回到配置文件的值，删除 {@code settings} 表里的 {@code agent.permission-mode} 行即可。
 */
@Component
public class PermissionSettings {
    private static final Logger log = LoggerFactory.getLogger(PermissionSettings.class);
    private static final String KEY = "agent.permission-mode";

    private final SettingsStore store;
    private final AtomicReference<PermissionMode> mode;

    public PermissionSettings(AgentProperties properties, SettingsStore store) {
        this.store = store;
        this.mode = new AtomicReference<>(restore(properties.permissionMode()));
    }

    public PermissionMode mode() {
        PermissionMode current = mode.get();
        return current == null ? PermissionMode.READ_ONLY : current;
    }

    /** 保存并立即生效；返回切换前的档位。 */
    public PermissionMode update(PermissionMode next) {
        Objects.requireNonNull(next, "mode must not be null");
        store.save(KEY, next.name());
        return mode.getAndSet(next);
    }

    private PermissionMode restore(PermissionMode fallback) {
        return store.find(KEY)
                .map(saved -> parse(saved, fallback))
                .orElseGet(() -> {
                    log.info("权限档位使用配置文件默认值: {}", fallback);
                    return fallback;
                });
    }

    private static PermissionMode parse(String saved, PermissionMode fallback) {
        try {
            PermissionMode restored = PermissionMode.valueOf(saved.strip().toUpperCase());
            log.info("权限档位使用库中保存的值: {}", restored);
            return restored;
        } catch (IllegalArgumentException e) {
            log.warn("库中的权限档位无法识别（{}），回退到配置文件默认值 {}", saved, fallback);
            return fallback;
        }
    }
}
