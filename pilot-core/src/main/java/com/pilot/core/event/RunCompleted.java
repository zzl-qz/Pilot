package com.pilot.core.event;

import java.util.List;

/** 运行完成：结果摘要、变更文件与已执行的验证项。 */
public record RunCompleted(String summary, List<String> changedFiles, List<String> checks) implements SessionEvent {
    public RunCompleted {
        changedFiles = List.copyOf(changedFiles == null ? List.of() : changedFiles);
        checks = List.copyOf(checks == null ? List.of() : checks);
    }

    @Override
    public EventType type() {
        return EventType.RUN_COMPLETED;
    }
}
