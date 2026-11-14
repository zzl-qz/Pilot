package com.pilot.server.run;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

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

    private final Map<String, String> runWorkspaces = new HashMap<>();
    private final Map<String, String> workspaceOperations = new HashMap<>();

    /**
     * 抢占会话的运行槽位。
     *
     * @return false 表示该会话已经有别的运行在跑，本次没有占位
     */
    public boolean claim(String runId, String sessionId) {
        return claim(runId, sessionId, null);
    }

    public synchronized boolean claim(String runId, String sessionId, Path workspace) {
        String key = workspace == null ? null : workspaceKey(workspace);
        if (key != null && workspaceOperations.entrySet().stream()
            .anyMatch(entry -> overlaps(key, entry.getKey()) && !runId.equals(entry.getValue()))) {
            return false;
        }
        String existing = sessionToRun.putIfAbsent(sessionId, runId);
        if (existing != null && !existing.equals(runId)) {
            return false;
        }
        runToSession.put(runId, sessionId);
        if (key != null) {
            runWorkspaces.put(runId, key);
        }
        return true;
    }

    /** 无条件登记（覆盖同会话的旧 run），仅用于内部自动续跑。 */
    public void register(String runId, String sessionId) {
        register(runId, sessionId, null);
    }

    public synchronized void register(String runId, String sessionId, Path workspace) {
        String key = workspace == null ? null : workspaceKey(workspace);
        if (key != null && workspaceOperations.entrySet().stream()
            .anyMatch(entry -> overlaps(key, entry.getKey()) && !runId.equals(entry.getValue()))) {
            throw new IllegalStateException("工作区正在回退或切换分支");
        }
        if (key != null) {
            runWorkspaces.put(runId, key);
        }
        runToSession.put(runId, sessionId);
        sessionToRun.put(sessionId, runId);
    }

    public synchronized void unregister(String runId) {
        runWorkspaces.remove(runId);
        workspaceOperations.values().removeIf(runId::equals);
        String sessionId = runToSession.remove(runId);
        if (sessionId != null) {
            sessionToRun.remove(sessionId, runId);
        }
    }

    /** 删除会话时清掉可能残留的槽位。 */
    public synchronized void forget(String sessionId) {
        String runId = sessionToRun.remove(sessionId);
        if (runId != null) {
            unregister(runId);
        }
    }

    public synchronized boolean lockWorkspace(String operationId, Path workspace) {
        String key = workspaceKey(workspace);
        if (workspaceOperations.entrySet().stream()
            .anyMatch(entry -> overlaps(key, entry.getKey()) && !operationId.equals(entry.getValue()))) {
            return false;
        }
        if (runWorkspaces.entrySet().stream()
            .anyMatch(entry -> overlaps(key, entry.getValue()) && !operationId.equals(entry.getKey()))) {
            return false;
        }
        workspaceOperations.put(key, operationId);
        return true;
    }

    private static String workspaceKey(Path workspace) {
        try {
            return workspace.toRealPath().toString();
        } catch (IOException e) {
            return workspace.toAbsolutePath().normalize().toString();
        }
    }

    /** 父目录与子目录共享文件，恢复期间必须使用同一套互斥边界。 */
    private static boolean overlaps(String first, String second) {
        Path left = Path.of(first);
        Path right = Path.of(second);
        return left.startsWith(right) || right.startsWith(left);
    }

    public Optional<String> activeRun(String sessionId) {
        return sessionId == null ? Optional.empty() : Optional.ofNullable(sessionToRun.get(sessionId));
    }

    public synchronized Map<String, String> activeRuns() {
        return Map.copyOf(runToSession);
    }
}
