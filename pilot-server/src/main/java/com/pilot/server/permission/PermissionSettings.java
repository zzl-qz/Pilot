package com.pilot.server.permission;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import com.pilot.core.permission.PermissionMode;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.store.SettingsStore;

import lombok.extern.slf4j.Slf4j;

/**
 * 当前生效的权限档位。默认与 Codex / OpenCode 一致：完全放行（{@code AUTO}）。
 *
 * <p>优先级：库中保存的用户选择 &gt; 配置文件 {@code pilot.agent.permission-mode}。
 * 通过 {@code PUT /api/settings/permission-mode} 切换后立即生效并落盘，重启后仍然保留；
 * 想回到配置文件的值，删除 {@code settings} 表里的 {@code agent.permission-mode} 行即可。
 */
@Component
@Slf4j
public class PermissionSettings {
    private static final String KEY = "agent.permission-mode";

    private final SettingsStore store;
    private final AtomicReference<PermissionMode> mode;
    private final AtomicReference<PermissionMode> lastReported = new AtomicReference<>();

    public PermissionSettings(AgentProperties properties, SettingsStore store) {
        this.store = store;
        this.mode = new AtomicReference<>(restore(properties.permissionMode()));
    }

    public PermissionMode mode() {
        PermissionMode current = mode.get();
        PermissionMode effective = current == null ? PermissionMode.AUTO : current;
        // 只读档位下每次写入与命令都会被拒绝，任务几乎不可能做完；档位变化时提示一次即可。
        if (effective != lastReported.getAndSet(effective)) {
            log.info("权限档位生效值: {}", effective);
            if (effective == PermissionMode.READ_ONLY) {
                log.warn("READ_ONLY 下写入与命令都会被拒绝，实现类任务无法完成；请在界面切换到「完全放行」。");
            }
        }
        return effective;
    }

    /** 保存并立即生效；返回切换前的档位。 */
    public PermissionMode update(PermissionMode next) {
        Objects.requireNonNull(next, "mode must not be null");
        store.save(KEY, next.name());
        return mode.getAndSet(next);
    }

    private PermissionMode restore(PermissionMode fallback) {
        return store.find(KEY).map(saved -> parse(saved, fallback)).orElseGet(() -> {
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
