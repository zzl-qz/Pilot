package com.pilot.core.multi;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 单个子 Agent 的变更沙箱。
 *
 * <p>所有写操作只进这里，{@link #add} 负责生成 diff；真实工作区始终只读。
 * 一个沙箱对应一个任务（或一个并行方案），可以单独丢弃、单独回滚，不影响其他沙箱。
 */
public final class DiffSandbox {
    private final String id;
    private final String agentId;
    private final String taskId;
    private final String baseCommit;
    private final List<SandboxChange> changes = new ArrayList<>();

    public DiffSandbox(String id, String agentId, String taskId, String baseCommit) {
        this.id = requireText(id, "sandboxId");
        this.agentId = requireText(agentId, "agentId");
        this.taskId = requireText(taskId, "taskId");
        this.baseCommit = baseCommit == null ? "" : baseCommit.strip();
    }

    public String id() {
        return id;
    }

    public String agentId() {
        return agentId;
    }

    public String taskId() {
        return taskId;
    }

    public String baseCommit() {
        return baseCommit;
    }

    public synchronized List<SandboxChange> changes() {
        return List.copyOf(changes);
    }

    public synchronized boolean isEmpty() {
        return changes.isEmpty();
    }

    /**
     * 记一次改动。同一路径被同一子 Agent 再次修改时，用最新内容覆盖旧条目，
     * 但 diff 始终相对「基线 + 本沙箱此前改动」的最终状态，避免一条路径出现两份互相矛盾的补丁。
     */
    public synchronized SandboxChange add(String path, String oldText, String newText,
                                          boolean existed, RiskLevel risk) {
        String diff = UnifiedDiff.create(path, existed ? oldText : null, newText);
        if (UnifiedDiff.isEmpty(diff)) {
            return null;
        }
        SandboxChange change = new SandboxChange(path, diff, newText, existed,
                fingerprint(oldText), agentId, taskId, risk, ChangeStatus.PENDING);
        SandboxChange superseded = null;
        for (SandboxChange existing : changes) {
            if (existing.path().equals(change.path())) {
                superseded = existing;
                break;
            }
        }
        if (superseded != null) {
            changes.remove(superseded);
        }
        changes.add(change);
        return change;
    }

    public synchronized int fileCount() {
        return changes.size();
    }

    /** 审阅后回写状态用；保持顺序不变。 */
    public synchronized void replaceAll(java.util.Collection<SandboxChange> updated) {
        changes.clear();
        if (updated != null) {
            changes.addAll(updated);
        }
    }

    /** 内容指纹：接受变更前用它确认文件没被其他人改过，避免覆盖用户手上的修改。 */
    public static String fingerprint(String text) {
        if (text == null) {
            return "";
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }

    @Override
    public String toString() {
        return "DiffSandbox{" + id + ", agent=" + agentId + ", task=" + taskId
                + ", files=" + fileCount() + '}';
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DiffSandbox sandbox && sandbox.id.equals(id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
