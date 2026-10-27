package com.pilot.server.session;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 进程内活跃运行索引：用于断线重连后校准界面状态。
 *
 * <p>{@link #claim} 是抢占式入口：同一会话已经有运行时不会覆盖，调用方据此拒绝并发的启动请求。
 * {@link #register} 保留覆盖语义，供内部「自动续跑」在旧 run 尚未注销时接管槽位。
 */
@Component
public class RunRegistry {
    private final ConcurrentMap<String, String> sessionToRun = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> runToSession = new ConcurrentHashMap<>();

    /**
     * 抢占会话的运行槽位。
     *
     * @return false 表示该会话已经有别的运行在跑，本次没有占位
     */
    public boolean claim(String runId, String sessionId) {
        String existing = sessionToRun.putIfAbsent(sessionId, runId);
        if (existing != null && !existing.equals(runId)) {
            return false;
        }
        runToSession.put(runId, sessionId);
        return true;
    }

    /** 无条件登记（覆盖同会话的旧 run），仅用于内部自动续跑。 */
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

    /** 删除会话时清掉可能残留的槽位。 */
    public void forget(String sessionId) {
        String runId = sessionToRun.remove(sessionId);
        if (runId != null) {
            runToSession.remove(runId, sessionId);
        }
    }

    public Optional<String> activeRun(String sessionId) {
        return sessionId == null ? Optional.empty() : Optional.ofNullable(sessionToRun.get(sessionId));
    }
}
