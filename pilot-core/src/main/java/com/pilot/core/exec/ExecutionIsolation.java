package com.pilot.core.exec;

import java.util.Locale;

/** 命令执行时的隔离级别。 */
public enum ExecutionIsolation {
    /** 与 Pilot 同用户的普通子进程：只有应用层权限检查，没有系统边界。 */
    LOCAL("local"),
    /** 容器内执行：独立文件系统、网络与资源上限，工作区以卷挂载。 */
    CONTAINER("container");

    private final String wireName;

    ExecutionIsolation(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static ExecutionIsolation parse(String value) {
        if (value == null || value.isBlank()) {
            return LOCAL;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        for (ExecutionIsolation isolation : values()) {
            if (isolation.wireName.equals(normalized)) {
                return isolation;
            }
        }
        return LOCAL;
    }
}
