package com.pilot.server.schedule;

import java.time.Instant;
import java.util.List;

import com.pilot.server.session.TaskControlService;

/** 一次性计划；同一个目标可以因等待外部事件被多次唤醒，历史不覆盖。 */
public record ScheduledTask(String id, String name, String sessionId, String taskId, String content,
    TaskControlService.Requirements requirements, String skill, Instant runAt, String timezone, String state,
    String runId, int attempts, int recoveries, Instant deadline, String message, List<Invocation> history,
    long version, Workspace workspace, Instant endedAt) {
    public ScheduledTask(String id, String name, String sessionId, String taskId, String content,
        TaskControlService.Requirements requirements, String skill, Instant runAt, String timezone, String state,
        String runId, int attempts, int recoveries, Instant deadline, String message, List<Invocation> history,
        long version, Workspace workspace) {
        this(id, name, sessionId, taskId, content, requirements, skill, runAt, timezone, state, runId, attempts,
            recoveries, deadline, message, history, version, workspace, null);
    }

    public ScheduledTask ended(Instant at) {
        return new ScheduledTask(id, name, sessionId, taskId, content, requirements, skill, runAt, timezone, state,
            runId, attempts, recoveries, deadline, message, history, version, workspace, at);
    }

    public record Workspace(String mode, String path, String base, String branch, String commit, Instant handedOffAt) {
    }

    public ScheduledTask(String id, String name, String sessionId, String taskId, String content,
        TaskControlService.Requirements requirements, String skill, Instant runAt, String timezone, String state,
        String runId, int attempts, int recoveries, Instant deadline, String message, List<Invocation> history,
        long version) {
        this(id, name, sessionId, taskId, content, requirements, skill, runAt, timezone, state, runId, attempts,
            recoveries, deadline, message, history, version, null);
    }

    public ScheduledTask withWorkspace(Workspace value) {
        return new ScheduledTask(id, name, sessionId, taskId, content, requirements, skill, runAt, timezone, state,
            runId, attempts, recoveries, deadline, message, history, version + 1, value, endedAt);
    }

    public record Invocation(String runId, Instant startedAt, String status) {
    }

    public ScheduledTask {
        history = history == null ? List.of() : List.copyOf(history);
    }

    public ScheduledTask change(String state, String taskId, String runId, Instant at, Instant deadline, int attempts,
        int recoveries, String message, List<Invocation> history) {
        return new ScheduledTask(id, name, sessionId, taskId, content, requirements, skill, at, timezone, state, runId,
            attempts, recoveries, deadline, message, history, version + 1, workspace, endedAt);
    }
}
