package com.pilot.server.agent;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

/** 会话级延迟工具快照，由运行服务显式更新。 */
@Component
public final class DeferredToolRegistry {
    private final ConcurrentMap<String, List<String>> toolsBySession = new ConcurrentHashMap<>();

    public void remember(String sessionId, List<String> toolNames) {
        toolsBySession.put(sessionId, List.copyOf(toolNames));
    }

    public List<String> snapshot(String sessionId) {
        return toolsBySession.get(sessionId);
    }

    public void forget(String sessionId) {
        toolsBySession.remove(sessionId);
    }
}
