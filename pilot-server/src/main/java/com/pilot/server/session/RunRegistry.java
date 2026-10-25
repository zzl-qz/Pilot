package com.pilot.server.session;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 进程内活跃运行索引：用于断线重连后校准界面状态。 */
@Component
public class RunRegistry {
    private final ConcurrentMap<String, String> sessionToRun = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> runToSession = new ConcurrentHashMap<>();

    public void register(String runId, String sessionId) {
        runToSession.put(runId, sessionId);
        sessionToRun.put(sessionId, runId);
    }

    public void unregister(String runId) {
        String sessionId = runToSession.remove(runId);
        if (sessionId != null) {
            sessionToRun.remove(sessionId, runId);
        }
    }

    public Optional<String> activeRun(String sessionId) {
        return sessionId == null ? Optional.empty() : Optional.ofNullable(sessionToRun.get(sessionId));
    }
}
