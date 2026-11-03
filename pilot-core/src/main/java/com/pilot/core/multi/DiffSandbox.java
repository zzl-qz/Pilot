package com.pilot.core.multi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    /**
     * 每个路径首次进入沙箱时的原文（新文件为 {@code null}）。
     *
     * <p>同一任务内多次编辑必须有共同的比较基准：否则第二次编辑会把「上一版提案」当成原文，
     * 既让 diff 只剩最后一处改动，也让 {@code baseHash} 变成提案的哈希，
     * 接受时与服务端校验的真实文件指纹对不上。
     */
    private final Map<String, String> baselines = new HashMap<>();

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

    public synchronized boolean hasChange(String path) {
        return changes.stream().anyMatch(change -> samePath(change.path(), path));
    }

    public synchronized String currentText(String path, String baselineText) {
        return changes.stream().filter(change -> samePath(change.path(), path))
                .findFirst().map(SandboxChange::newContent).orElse(baselineText);
    }

    public synchronized String proposedText(String path) {
        return changes.stream().filter(change -> samePath(change.path(), path))
                .findFirst().map(SandboxChange::newContent).orElse(null);
    }

    private static boolean samePath(String left, String right) {
        return left != null && right != null
                && left.replace('\\', '/').equalsIgnoreCase(right.replace('\\', '/'));
    }

    /**
     * 记一次改动。同一路径被同一子 Agent 再次修改时，用最新内容覆盖旧条目，
     * 但 diff 始终相对「首次进入沙箱时的原文」计算，{@code baseHash} 也一直是原文的指纹。
     *
     * <p>写回真实文件时服务端会重新读取文件并比对该指纹；如果这里用「上一版提案」当基线，
     * 连续改两处就会因指纹不符被拒绝，表现为第一处改动丢失。
     */
    public synchronized SandboxChange add(String path, String oldText, String newText,
                                          boolean existed, RiskLevel risk) {
        String key = normalize(path);
        SandboxChange superseded = null;
        for (SandboxChange existing : changes) {
            if (samePath(existing.path(), path)) {
                superseded = existing;
                break;
            }
        }
        if (superseded == null) {
            baselines.put(key, existed ? oldText : null);
        }
        boolean baselineExisted = superseded != null ? superseded.existed() : existed;
        String baseline = baselines.containsKey(key) ? baselines.get(key) : (existed ? oldText : null);
        String diff = UnifiedDiff.create(path, baselineExisted ? baseline : null, newText);
        if (UnifiedDiff.isEmpty(diff)) {
            // 改回原文：不留一条空改动，也不留下过期条目
            if (superseded != null) {
                changes.remove(superseded);
                baselines.remove(key);
            }
            return null;
        }
        String baseHash = superseded != null ? superseded.baseHash() : fingerprint(baseline);
        SandboxChange change = new SandboxChange(path, diff, newText, baselineExisted,
                baseHash, agentId, taskId, risk, ChangeStatus.PENDING);
        if (superseded != null) {
            changes.remove(superseded);
        }
        changes.add(change);
        return change;
    }

    private static String normalize(String path) {
        return path == null ? "" : path.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
    }

    public synchronized int fileCount() {
        return changes.size();
    }

    public synchronized String candidateHash() {
        String canonical = changes.stream().sorted(java.util.Comparator.comparing(SandboxChange::path))
                .map(change -> change.path() + "\0" + change.baseHash() + "\0" + change.newContent()
                        + "\0" + change.status().wireName())
                .collect(java.util.stream.Collectors.joining("\n"));
        return fingerprint(canonical);
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
