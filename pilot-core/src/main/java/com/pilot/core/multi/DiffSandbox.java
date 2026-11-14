package com.pilot.core.multi;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.pilot.core.util.Sha256;
import com.pilot.core.workspace.WorkspacePathIdentity;

/**
 * 单个子 Agent 的变更沙箱。
 *
 * <p>文件内容修改只进这里，{@link #add} 负责生成 diff；接受提案前不修改真实业务文件。
 * 一个沙箱对应一个任务（或一个并行方案），可以单独丢弃、单独回滚，不影响其他沙箱。
 */
public final class DiffSandbox {
    private final String id;
    private final String agentId;
    private final String taskId;
    private final String baseCommit;
    private final WorkspacePathIdentity paths;
    private final Map<String, SandboxChange> changes = new LinkedHashMap<>();
    /**
     * 每个路径首次进入沙箱时的原文（新文件为 {@code null}）。
     *
     * <p>同一任务内多次编辑必须有共同的比较基准：否则第二次编辑会把「上一版提案」当成原文，
     * 既让 diff 只剩最后一处改动，也让 {@code baseHash} 变成提案的哈希，
     * 接受时与服务端校验的真实文件指纹对不上。
     */
    private final Map<String, String> baselines = new HashMap<>();

    public DiffSandbox(String id, String agentId, String taskId, String baseCommit) {
        this(id, agentId, taskId, baseCommit, null);
    }

    public DiffSandbox(String id, String agentId, String taskId, String baseCommit, Path workspace) {
        this.id = requireText(id, "sandboxId");
        this.agentId = requireText(agentId, "agentId");
        this.taskId = requireText(taskId, "taskId");
        this.baseCommit = baseCommit == null ? "" : baseCommit.strip();
        this.paths = workspace == null ? null : new WorkspacePathIdentity(workspace);
    }

    public Path workspace() {
        return paths == null ? null : paths.root();
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
        return List.copyOf(changes.values());
    }

    public synchronized boolean isEmpty() {
        return changes.isEmpty();
    }

    public synchronized boolean hasChange(String path) {
        return !changes.isEmpty() && changes.containsKey(key(path));
    }

    public synchronized String currentText(String path, String baselineText) {
        SandboxChange change = changes.isEmpty() ? null : changes.get(key(path));
        return change == null ? baselineText : change.newContent();
    }

    /** 沿用首次提案的拼写，让虚拟文件的读取记录与后续编辑指向同一路径。 */
    public synchronized String canonicalPath(String path) {
        SandboxChange change = changes.isEmpty() ? null : changes.get(key(path));
        return change == null ? path : change.path();
    }

    public synchronized String proposedText(String path) {
        return currentText(path, null);
    }

    /**
     * 记一次改动。同一路径被同一子 Agent 再次修改时，用最新内容覆盖旧条目，
     * 但 diff 始终相对「首次进入沙箱时的原文」计算，{@code baseHash} 也一直是原文的指纹。
     *
     * <p>写回真实文件时服务端会重新读取文件并比对该指纹；如果这里用「上一版提案」当基线，
     * 连续改两处就会因指纹不符被拒绝，表现为第一处改动丢失。
     */
    public synchronized SandboxChange add(String path, String oldText, String newText, boolean existed,
        RiskLevel risk) {
        String key = key(path);
        SandboxChange superseded = changes.get(key);
        path = superseded == null ? path : superseded.path();
        if (superseded == null) {
            baselines.put(key, existed ? oldText : null);
        }
        boolean baselineExisted = superseded != null ? superseded.existed() : existed;
        String baseline = baselines.containsKey(key) ? baselines.get(key) : (existed ? oldText : null);
        String diff = UnifiedDiff.create(path, baselineExisted ? baseline : null, newText);
        if (UnifiedDiff.isEmpty(diff)) {
            // 改回原文：不留一条空改动，也不留下过期条目
            changes.remove(key);
            baselines.remove(key);
            return null;
        }
        String baseHash = superseded != null ? superseded.baseHash() : fingerprint(baseline);
        SandboxChange change = new SandboxChange(path, diff, newText, baselineExisted, baseHash, agentId, taskId, risk,
            ChangeStatus.PENDING);
        // 保持原来的展示顺序：最近一次编辑的文件移到末尾。
        changes.remove(key);
        changes.put(key, change);
        return change;
    }

    private String key(String path) {
        String normalized = path == null ? "" : path.replace('\\', '/');
        return paths == null || normalized.isEmpty() ? normalized : paths.key(normalized);
    }

    public synchronized int fileCount() {
        return changes.size();
    }

    public synchronized String candidateHash() {
        String canonical = changes
            .values().stream().sorted(Comparator.comparing(SandboxChange::path)).map(change -> change.path() + "\0"
                + change.baseHash() + "\0" + change.newContent() + "\0" + change.status().wireName())
            .collect(Collectors.joining("\n"));
        return fingerprint(canonical);
    }

    /** 审阅后回写状态用；保持顺序不变。 */
    public synchronized void replaceAll(Collection<SandboxChange> updated) {
        Map<String, SandboxChange> next = new LinkedHashMap<>();
        if (updated != null) {
            for (SandboxChange change : updated) {
                if (next.put(key(change.path()), change) != null) {
                    throw new IllegalArgumentException("沙箱中存在重复路径：" + change.path());
                }
            }
        }
        changes.clear();
        changes.putAll(next);
        baselines.keySet().retainAll(changes.keySet());
    }

    /** 内容指纹：接受变更前用它确认文件没被其他人改过，避免覆盖用户手上的修改。 */
    public static String fingerprint(String text) {
        return text == null ? "" : Sha256.hex(text);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }

    @Override
    public String toString() {
        return "DiffSandbox{" + id + ", agent=" + agentId + ", task=" + taskId + ", files=" + fileCount() + '}';
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
