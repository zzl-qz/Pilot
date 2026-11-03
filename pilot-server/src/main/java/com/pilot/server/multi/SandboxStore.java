package com.pilot.server.multi;

import com.pilot.core.multi.ChangeStatus;
import com.pilot.core.multi.DiffSandbox;
import com.pilot.core.multi.SandboxChange;
import com.pilot.core.workspace.PathValidator;
import com.pilot.server.config.PilotDataDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 会话级的沙箱与任务看板存储。
 *
 * <p>沙箱改动只保存在内存并同步写一份 JSON 审计副本（{@code .pilot/multi/<session>/}），
 * 真实工作区在用户点「接受变更」之前始终保持只读。
 */
@Component
public class SandboxStore {
    private static final Logger log = LoggerFactory.getLogger(SandboxStore.class);

    /** 界面展示用的改动视图：不含完整文件内容，避免大文件把 SSE / JSON 拖垮。 */
    public record DiffView(String sandboxId, String agentId, String taskId, String path, String diff,
                           int added, int removed, boolean existed, String risk, String status) {
    }

    /** 任务卡片。 */
    public record TaskView(String taskId, String title, String agentId, String status, String detail,
                           int files) {
    }

    /** 待仲裁事项。 */
    public record Arbitration(String id, String kind, String taskId, List<String> options, String report) {
    }

    /** 界面展示用的清单摘要（不含文件内容）。 */
    public record ArtifactView(String sandboxId, String taskId, int revision, String candidateHash,
                               String environmentFingerprint, List<String> dependencyHashes,
                               int fileCount, java.time.Instant createdAt) {
        static ArtifactView of(com.pilot.core.multi.ArtifactManifest manifest) {
            return new ArtifactView(manifest.sandboxId(), manifest.taskId(), manifest.revision(),
                    manifest.candidateHash(), manifest.environmentFingerprint(),
                    manifest.dependencyHashes(), manifest.files().size(), manifest.createdAt());
        }
    }

    /**
     * 校验状态：{@code current=false} 表示曾经通过，但候选内容、依赖或环境已经变了。
     *
     * @param reason 面向用户的失效原因；通过且仍然有效时为空串
     */
    public record VerificationState(String status, boolean current, String candidateHash, String reason) {
        public boolean ok() {
            return current && VerificationGate.Report.PASSED.equals(status);
        }
    }

    /** 接受/拒绝变更的处理结果。 */
    public record ApplyReport(boolean ok, List<String> written, List<String> skipped, List<String> failed,
                              String message) {
    }

    private final ConcurrentMap<String, List<DiffSandbox>> sandboxes = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<TaskView>> tasks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<Arbitration>> arbitrations = new ConcurrentHashMap<>();
    /** 每个沙箱最近一次硬校验结果，键为 {@code sessionId/sandboxId}。 */
    private final ConcurrentMap<String, VerificationGate.Report> verifications = new ConcurrentHashMap<>();
    /** 每个沙箱的产物清单历史；最后一项是当前版本。 */
    private final ConcurrentMap<String, List<com.pilot.core.multi.ArtifactManifest>> manifests =
            new ConcurrentHashMap<>();
    /** 下游沙箱 → 上游沙箱列表，用于依赖变更传播。 */
    private final ConcurrentMap<String, java.util.LinkedHashSet<String>> dependencies =
            new ConcurrentHashMap<>();
    /** 会话 → 工作区版本；沙箱被接受一次就递增，用来让其余沙箱的旧校验失效。 */
    private final ConcurrentMap<String, Long> workspaceRevisions = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final PilotDataDir dataDir;
    private final Set<String> restoredSessions = ConcurrentHashMap.newKeySet();

    private record SandboxPayload(String sandboxId, String agentId, String taskId, String baseCommit,
                                  List<SandboxChange> changes) {
    }

    private record TaskPayload(String taskId, String title, String agentId, String status,
                               String detail, int files) {
    }

    /**
     * @param workspaceRevision 记录校验时所处的工作区版本；任一沙箱被接受后工作区基线就变了，
     *                          其余沙箱的旧结论随即失效（「选择一部分改动接受」必须重新校验）
     */
    private record VerificationPayload(String candidateHash, String dependencyHash,
                                       String environmentFingerprint, String validationHash,
                                       String status, List<String> checks, String detail,
                                       long workspaceRevision) {
    }

    public SandboxStore(ObjectMapper mapper, PilotDataDir dataDir) {
        this.mapper = mapper;
        this.dataDir = dataDir;
    }

    public void reset(String sessionId) {
        sandboxes.remove(sessionId);
        tasks.remove(sessionId);
        arbitrations.remove(sessionId);
        verifications.keySet().removeIf(key -> key.startsWith(sessionId + "/"));
        manifests.keySet().removeIf(key -> key.startsWith(sessionId + "/"));
        dependencies.keySet().removeIf(key -> key.startsWith(sessionId + "/"));
        workspaceRevisions.remove(sessionId);
        restoredSessions.remove(sessionId);
        Path dir = sessionDir(sessionId);
        if (Files.isDirectory(dir)) {
            try (var paths = Files.walk(dir)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            } catch (IOException e) {
                throw new java.io.UncheckedIOException("清理多 Agent 会话状态失败", e);
            }
        }
    }

    /** 记录最近一次硬校验结果，供界面说明「为什么不能接受」。 */
    public void recordVerification(String sessionId, String sandboxId, VerificationGate.Report report) {
        recordVerification(sessionId, sandboxId, environmentFingerprint(), report);
    }

    /**
     * 记录校验结果，并把它绑定到「当前候选清单 + 依赖清单 + 环境」。
     *
     * <p>任何一项变化都会让旧结果失效：候选内容变了说明验的不是这份代码；
     * 上游依赖变了说明下游是拿旧接口验的；环境变了（工具链、命令）说明结论不可复现。
     */
    public void recordVerification(String sessionId, String sandboxId, String environmentFingerprint,
                                   VerificationGate.Report report) {
        if (report == null) {
            return;
        }
        DiffSandbox sandbox = find(sessionId, sandboxId).orElse(null);
        if (sandbox == null) {
            return;
        }
        com.pilot.core.multi.ArtifactManifest manifest =
                manifest(sessionId, sandboxId, environmentFingerprint);
        verifications.put(sessionId + "/" + sandboxId, report);
        persistVerification(sessionId, sandboxId, manifest, report);
        recordManifest(sessionId, manifest);
        // 上游产物迭代后，依赖它的下游校验结果随即失效
        invalidateDownstream(sessionId, sandboxId);
    }

    /** 没有跑过校验、或校验针对的已经不是当前产物时返回 null。 */
    public VerificationGate.Report verification(String sessionId, String sandboxId) {
        VerificationState state = verificationState(sessionId, sandboxId);
        return state.current() ? verifications.get(sessionId + "/" + sandboxId) : null;
    }

    /** 校验为什么可用/不可用：界面和接受接口都要能说清楚。 */
    public VerificationState verificationState(String sessionId, String sandboxId) {
        restore(sessionId);
        DiffSandbox sandbox = find(sessionId, sandboxId).orElse(null);
        if (sandbox == null) {
            return new VerificationState("missing", false, "", "没有这个沙箱: " + sandboxId);
        }
        VerificationPayload payload = readVerification(sessionId, sandboxId);
        if (payload == null) {
            verifications.remove(sessionId + "/" + sandboxId);
            return new VerificationState("missing", false, "", "还没有执行过校验");
        }
        String environment = payload.environmentFingerprint() == null ? environmentFingerprint()
                : payload.environmentFingerprint();
        // 先判工作区：接受过别的改动后，这份结论所对照的工作区已经不是现在的了
        if (payload.workspaceRevision() != workspaceRevision(sessionId)) {
            verifications.remove(sessionId + "/" + sandboxId);
            return new VerificationState(payload.status(), false, "",
                    "工作区已被其他已接受的变更修改，需要重新校验");
        }
        com.pilot.core.multi.ArtifactManifest manifest = manifest(sessionId, sandboxId, environment);
        // 先判依赖：候选指纹本身也包含依赖，先判候选会把「上游变了」误报成「自己变了」
        if (!manifest.dependencyHashes().equals(payload.dependencyHash() == null
                ? List.of() : parseDependencies(payload.dependencyHash()))) {
            verifications.remove(sessionId + "/" + sandboxId);
            return new VerificationState(payload.status(), false, manifest.candidateHash(),
                    "上游依赖产物已变化，需要重新校验");
        }
        if (!manifest.candidateHash().equals(payload.candidateHash())) {
            verifications.remove(sessionId + "/" + sandboxId);
            return new VerificationState(payload.status(), false, manifest.candidateHash(),
                    "候选内容已变化，旧校验结果不再适用");
        }
        return new VerificationState(payload.status(), true, manifest.candidateHash(), "");
    }

    /**
     * 当前候选清单。依赖取自登记的依赖关系，环境指纹默认用当前 JVM 与操作系统标识。
     */
    public com.pilot.core.multi.ArtifactManifest manifest(String sessionId, String sandboxId,
                                                          String environmentFingerprint) {
        DiffSandbox sandbox = find(sessionId, sandboxId).orElseThrow(
                () -> new IllegalArgumentException("没有这个沙箱: " + sandboxId));
        List<com.pilot.core.multi.ArtifactFile> files = sandbox.changes().stream()
                .map(change -> new com.pilot.core.multi.ArtifactFile(change.path(), change.baseHash(),
                        DiffSandbox.fingerprint(change.newContent()),
                        change.newContent() == null ? 0
                                : change.newContent().getBytes(java.nio.charset.StandardCharsets.UTF_8).length))
                .toList();
        List<com.pilot.core.multi.ArtifactManifest> upstream = upstreamManifests(sessionId, sandboxId);
        List<String> dependencyHashes = upstream.stream()
                .map(com.pilot.core.multi.ArtifactManifest::candidateHash).toList();
        List<String> dependencyManifests = upstream.stream()
                .map(com.pilot.core.multi.ArtifactManifest::manifestId).toList();
        return com.pilot.core.multi.ArtifactManifest.create(sessionId, sandboxId, sandbox.taskId(),
                sandbox.baseCommit(), environmentFingerprint, files, dependencyManifests, dependencyHashes,
                revisionOf(sessionId, sandboxId) + 1, java.time.Instant.now());
    }

    /** 登记下游沙箱依赖哪些上游沙箱；上游候选变化时下游校验自动失效。 */
    public void linkDependency(String sessionId, String upstreamSandboxId, String downstreamSandboxId) {
        if (upstreamSandboxId == null || downstreamSandboxId == null
                || upstreamSandboxId.isBlank() || downstreamSandboxId.isBlank()) {
            return;
        }
        dependencies.computeIfAbsent(sessionId + "/" + downstreamSandboxId,
                key -> new java.util.LinkedHashSet<>()).add(upstreamSandboxId);
        persistDependencies(sessionId);
        invalidateDownstream(sessionId, upstreamSandboxId);
    }

    /**
     * 按任务依赖登记沙箱依赖：{@code dependsOn} 里的任务若已有沙箱，就把它们记为上游。
     * 同一任务有多个沙箱（重试、多方案）时全部登记，任一上游变化都会让下游重新校验。
     */
    public void linkTaskDependencies(String sessionId, List<String> dependsOn, String downstreamSandboxId) {
        if (dependsOn == null || dependsOn.isEmpty()) {
            return;
        }
        for (String taskId : dependsOn) {
            for (DiffSandbox upstream : sandboxes(sessionId)) {
                if (upstream.taskId().equals(taskId)) {
                    linkDependency(sessionId, upstream.id(), downstreamSandboxId);
                }
            }
        }
    }

    public List<String> dependenciesOf(String sessionId, String sandboxId) {
        restore(sessionId);
        return List.copyOf(dependencies.getOrDefault(sessionId + "/" + sandboxId, new java.util.LinkedHashSet<>()));
    }

    public List<ArtifactView> manifestHistory(String sessionId, String sandboxId) {
        restore(sessionId);
        List<com.pilot.core.multi.ArtifactManifest> history = manifests.getOrDefault(sessionId + "/" + sandboxId,
                List.of());
        return history.stream().map(ArtifactView::of).toList();
    }

    /** 最近两版的差异；只有一版时返回空差异。 */
    public com.pilot.core.multi.ArtifactDiff manifestDiff(String sessionId, String sandboxId) {
        List<com.pilot.core.multi.ArtifactManifest> history = manifests.getOrDefault(sessionId + "/" + sandboxId,
                List.of());
        if (history.size() < 2) {
            return manifest(sessionId, sandboxId, environmentFingerprint())
                    .diff(history.isEmpty() ? null : history.getLast());
        }
        return history.getLast().diff(history.get(history.size() - 2));
    }

    private List<com.pilot.core.multi.ArtifactManifest> upstreamManifests(String sessionId, String sandboxId) {
        List<com.pilot.core.multi.ArtifactManifest> result = new ArrayList<>();
        java.util.Set<String> visited = new java.util.LinkedHashSet<>();
        collectUpstream(sessionId, sandboxId, visited, result);
        return result;
    }

    private void collectUpstream(String sessionId, String sandboxId, java.util.Set<String> visited,
                                 List<com.pilot.core.multi.ArtifactManifest> collected) {
        for (String upstreamId : dependencies.getOrDefault(sessionId + "/" + sandboxId,
                new java.util.LinkedHashSet<>())) {
            if (!visited.add(upstreamId)) {
                continue;
            }
            DiffSandbox upstream = find(sessionId, upstreamId).orElse(null);
            if (upstream == null) {
                continue;
            }
            com.pilot.core.multi.ArtifactManifest manifest = latestManifest(sessionId, upstreamId)
                    .orElseGet(() -> manifest(sessionId, upstreamId, environmentFingerprint()));
            collected.add(manifest);
            collectUpstream(sessionId, upstreamId, visited, collected);
        }
    }

    private Optional<com.pilot.core.multi.ArtifactManifest> latestManifest(String sessionId, String sandboxId) {
        List<com.pilot.core.multi.ArtifactManifest> history = manifests.getOrDefault(sessionId + "/" + sandboxId,
                List.of());
        return history.isEmpty() ? Optional.empty() : Optional.of(history.getLast());
    }

    private int revisionOf(String sessionId, String sandboxId) {
        return manifests.getOrDefault(sessionId + "/" + sandboxId, List.of()).size();
    }

    /** 上游变了：下游的旧校验结果立即失效，并记录新修订供界面比较。 */
    private void invalidateDownstream(String sessionId, String upstreamSandboxId) {
        List<String> downstream = dependencies.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(sessionId + "/")
                        && entry.getValue().contains(upstreamSandboxId))
                .map(entry -> entry.getKey().substring(sessionId.length() + 1))
                .toList();
        for (String sandboxId : downstream) {
            // 保留旧结论本身，只更新清单：verificationState 会明确说明「依赖变了」，
            // 直接删掉只能得到「还没校验过」，丢掉了失效原因
            try {
                recordManifest(sessionId, manifest(sessionId, sandboxId, environmentFingerprint()));
            } catch (RuntimeException e) {
                log.debug("记录下游清单失败: session={}, sandbox={}", sessionId, sandboxId, e);
            }
        }
    }

    private void recordManifest(String sessionId, com.pilot.core.multi.ArtifactManifest manifest) {
        String key = sessionId + "/" + manifest.sandboxId();
        List<com.pilot.core.multi.ArtifactManifest> history =
                manifests.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>());
        if (!history.isEmpty() && history.getLast().candidateHash().equals(manifest.candidateHash())) {
            return;
        }
        history.add(manifest);
        persistManifests(sessionId, manifest.sandboxId());
    }

    /**
     * 工作区版本：每次有沙箱被接受就递增。
     *
     * <p>校验结论是「在某一版工作区上跑出来的」。用户只接受一部分改动时，剩余沙箱面对的
     * 已经是另一个工作区，旧的通过结论不能继续用来放行。
     */
    public long workspaceRevision(String sessionId) {
        restore(sessionId);
        return workspaceRevisions.getOrDefault(sessionId, 0L);
    }

    /** 有变更真正写入工作区后调用；返回递增后的版本。 */
    public long bumpWorkspaceRevision(String sessionId) {
        long next = workspaceRevisions.merge(sessionId, 1L, Long::sum);
        persistWorkspaceRevision(sessionId);
        return next;
    }

    private void persistWorkspaceRevision(String sessionId) {
        Path file = dataDir.file("multi", sanitize(sessionId), "workspace.json");
        try {
            Files.createDirectories(file.getParent());
            atomicWrite(file, mapper.writeValueAsString(
                    Map.of("workspaceRevision", workspaceRevisions.getOrDefault(sessionId, 0L))));
        } catch (IOException e) {
            log.warn("写入工作区版本失败: session={}", sessionId, e);
        }
    }

    private void restoreWorkspaceRevision(String sessionId, Path file) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            Map<String, Object> payload = mapper.readValue(Files.readString(file),
                    new tools.jackson.core.type.TypeReference<Map<String, Object>>() { });
            Object value = payload == null ? null : payload.get("workspaceRevision");
            if (value instanceof Number number) {
                workspaceRevisions.put(sessionId, number.longValue());
            }
        } catch (Exception e) {
            log.warn("忽略损坏的工作区版本文件: {}", file, e);
        }
    }

    public void add(String sessionId, DiffSandbox sandbox) {
        restoredSessions.add(sessionId);
        sandboxes.computeIfAbsent(sessionId, key -> new CopyOnWriteArrayList<>()).add(sandbox);
        persist(sessionId, sandbox);
    }

    public List<DiffSandbox> sandboxes(String sessionId) {
        restore(sessionId);
        return List.copyOf(sandboxes.getOrDefault(sessionId, List.of()));
    }

    public Optional<DiffSandbox> find(String sessionId, String sandboxId) {
        return sandboxes(sessionId).stream().filter(box -> box.id().equals(sandboxId)).findFirst();
    }

    /** 某次调度涉及的沙箱（按沙箱 id 前缀关联）。 */
    public List<DiffSandbox> sandboxesOf(String sessionId, List<String> sandboxIds) {
        return sandboxes(sessionId).stream().filter(box -> sandboxIds.contains(box.id())).toList();
    }

    public List<DiffView> diffs(String sessionId) {
        List<DiffView> views = new ArrayList<>();
        for (DiffSandbox sandbox : sandboxes(sessionId)) {
            for (SandboxChange change : sandbox.changes()) {
                views.add(new DiffView(sandbox.id(), change.agentId(), change.taskId(), change.path(),
                        change.diff(), change.added(), change.removed(), change.existed(),
                        change.risk().wireName(), change.status().wireName()));
            }
        }
        return List.copyOf(views);
    }

    public void recordTasks(String sessionId, List<TaskView> views) {
        tasks.put(sessionId, List.copyOf(views));
        persistTasks(sessionId, views);
    }

    public List<TaskView> tasks(String sessionId) {
        restore(sessionId);
        return List.copyOf(tasks.getOrDefault(sessionId, List.of()));
    }

    public void raise(String sessionId, Arbitration arbitration) {
        arbitrations.computeIfAbsent(sessionId, key -> new CopyOnWriteArrayList<>()).add(arbitration);
        persistArbitrations(sessionId);
    }

    public List<Arbitration> arbitrations(String sessionId) {
        restore(sessionId);
        return List.copyOf(arbitrations.getOrDefault(sessionId, List.of()));
    }

    /**
     * 接受某个沙箱的改动：逐文件写回工作区。
     *
     * <p>写入前重新校验路径与内容指纹：如果文件在子 Agent 读取之后被用户或其他改动修改过，
     * 直接拒绝而不是静默覆盖。
     */
    public ApplyReport accept(String sessionId, String sandboxId, Path workspace) {
        DiffSandbox sandbox = find(sessionId, sandboxId).orElse(null);
        if (sandbox == null) {
            return new ApplyReport(false, List.of(), List.of(), List.of(), "没有这个沙箱: " + sandboxId);
        }
        PathValidator paths;
        try {
            paths = new PathValidator(workspace);
        } catch (RuntimeException e) {
            return new ApplyReport(false, List.of(), List.of(), List.of(), "工作区不可用: " + e.getMessage());
        }

        List<String> written = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (SandboxChange change : sandbox.changes()) {
            if (change.status() != ChangeStatus.PENDING) {
                skipped.add(change.path() + "（" + change.status().wireName() + "）");
                continue;
            }
            try {
                Path file = paths.resolve(change.path());
                if (change.existed()) {
                    String current = Files.isRegularFile(file)
                            ? Files.readString(file, StandardCharsets.UTF_8)
                            : null;
                    if (current == null || !DiffSandbox.fingerprint(current).equals(change.baseHash())) {
                        failed.add(change.path() + "（文件已被修改，拒绝覆盖）");
                        continue;
                    }
                } else if (Files.exists(file)) {
                    failed.add(change.path() + "（文件已存在，拒绝覆盖）");
                    continue;
                }
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                Files.writeString(file, change.newContent(), StandardCharsets.UTF_8);
                written.add(change.path());
            } catch (IOException | RuntimeException e) {
                failed.add(change.path() + "（" + e.getMessage() + "）");
            }
        }
        boolean ok = failed.isEmpty();
        String message = ok ? "已接受 " + written.size() + " 个文件改动" : "部分文件未写入，请检查后重试";
        log.info("Accept sandbox {} for session {}: written={}, failed={}", sandboxId, sessionId,
                written.size(), failed.size());
        return new ApplyReport(ok, List.copyOf(written), List.copyOf(skipped), List.copyOf(failed), message);
    }

    /** 拒绝某个沙箱：只改状态，不碰工作区。 */
    public boolean reject(String sessionId, String sandboxId) {
        DiffSandbox sandbox = find(sessionId, sandboxId).orElse(null);
        if (sandbox == null) {
            return false;
        }
        for (SandboxChange change : sandbox.changes()) {
            if (change.status() == ChangeStatus.PENDING) {
                replace(sandbox, change.withStatus(ChangeStatus.REJECTED));
            }
        }
        persist(sessionId, sandbox);
        return true;
    }

    /** 标记某沙箱已被接受（落盘成功后调用）。 */
    public void markAccepted(String sessionId, String sandboxId) {
        find(sessionId, sandboxId).ifPresent(sandbox -> {
            for (SandboxChange change : sandbox.changes()) {
                if (change.status() == ChangeStatus.PENDING) {
                    replace(sandbox, change.withStatus(ChangeStatus.ACCEPTED));
                }
            }
            persist(sessionId, sandbox);
        });
    }

    private void replace(DiffSandbox sandbox, SandboxChange updated) {
        synchronized (sandbox) {
            List<SandboxChange> current = new ArrayList<>(sandbox.changes());
            for (int i = 0; i < current.size(); i++) {
                SandboxChange change = current.get(i);
                if (change.path().equals(updated.path()) && change.status() == ChangeStatus.PENDING) {
                    current.set(i, updated);
                }
            }
            sandbox.replaceAll(current);
        }
    }

    private void persist(String sessionId, DiffSandbox sandbox) {
        Path dir = sessionDir(sessionId);
        try {
            Files.createDirectories(dir);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sandboxId", sandbox.id());
            payload.put("agentId", sandbox.agentId());
            payload.put("taskId", sandbox.taskId());
            payload.put("baseCommit", sandbox.baseCommit());
            payload.put("changes", sandbox.changes());
            atomicWrite(dir.resolve(sandbox.id() + ".json"), mapper.writeValueAsString(payload));
        } catch (IOException e) {
            // 审计副本失败不影响主流程，但要在日志里留痕
            log.warn("写入沙箱审计副本失败: session={}, sandbox={}", sessionId, sandbox.id(), e);
        }
    }

    private void persistTasks(String sessionId, List<TaskView> views) {
        Path file = dataDir.file("multi", sanitize(sessionId), "tasks.json");
        try {
            Files.createDirectories(file.getParent());
            atomicWrite(file, mapper.writeValueAsString(views.stream()
                    .map(view -> new TaskPayload(view.taskId(), view.title(), view.agentId(), view.status(),
                            view.detail(), view.files())).toList()));
        } catch (IOException e) {
            log.warn("写入多 Agent 任务状态失败: session={}", sessionId, e);
        }
    }

    private void persistVerification(String sessionId, String sandboxId,
                                     com.pilot.core.multi.ArtifactManifest manifest,
                                     VerificationGate.Report report) {
        Path file = dataDir.file("multi", sanitize(sessionId), "verification-" + sanitize(sandboxId) + ".json");
        try {
            Files.createDirectories(file.getParent());
            atomicWrite(file, mapper.writeValueAsString(new VerificationPayload(manifest.candidateHash(),
                    String.join(",", manifest.dependencyHashes()), manifest.environmentFingerprint(),
                    validationHash(manifest.candidateHash(), manifest.environmentFingerprint(), report.checks()),
                    report.status(), report.checks(), report.detail(), workspaceRevision(sessionId))));
        } catch (IOException e) {
            log.warn("写入多 Agent 校验状态失败: session={}, sandbox={}", sessionId, sandboxId, e);
        }
    }

    private void persistManifests(String sessionId, String sandboxId) {
        Path file = dataDir.file("multi", sanitize(sessionId), "manifests-" + sanitize(sandboxId) + ".json");
        try {
            Files.createDirectories(file.getParent());
            atomicWrite(file, mapper.writeValueAsString(
                    manifests.getOrDefault(sessionId + "/" + sandboxId, List.of())));
        } catch (IOException e) {
            log.warn("写入产物清单历史失败: session={}, sandbox={}", sessionId, sandboxId, e);
        }
    }

    private void persistDependencies(String sessionId) {
        Path file = dataDir.file("multi", sanitize(sessionId), "dependencies.json");
        try {
            Files.createDirectories(file.getParent());
            Map<String, List<String>> payload = new LinkedHashMap<>();
            String prefix = sessionId + "/";
            dependencies.forEach((key, value) -> {
                if (key.startsWith(prefix)) {
                    payload.put(key.substring(prefix.length()), List.copyOf(value));
                }
            });
            atomicWrite(file, mapper.writeValueAsString(payload));
        } catch (IOException e) {
            log.warn("写入依赖关系失败: session={}", sessionId, e);
        }
    }

    private void persistArbitrations(String sessionId) {
        Path file = dataDir.file("multi", sanitize(sessionId), "arbitrations.json");
        try {
            Files.createDirectories(file.getParent());
            atomicWrite(file, mapper.writeValueAsString(arbitrations.getOrDefault(sessionId, List.of())));
        } catch (IOException e) {
            log.warn("写入多 Agent 仲裁状态失败: session={}", sessionId, e);
        }
    }

    private void restore(String sessionId) {
        if (!restoredSessions.add(sessionId)) return;
        Path dir = sessionDir(sessionId);
        if (!Files.isDirectory(dir)) return;
        try (var files = Files.list(dir)) {
            files.filter(file -> file.getFileName().toString().endsWith(".json"))
                    .filter(file -> !file.getFileName().toString().equals("tasks.json"))
                    .filter(file -> !file.getFileName().toString().equals("arbitrations.json"))
                    .filter(file -> !file.getFileName().toString().equals("dependencies.json"))
                    .filter(file -> !file.getFileName().toString().equals("workspace.json"))
                    .filter(file -> !file.getFileName().toString().startsWith("verification-"))
                    .filter(file -> !file.getFileName().toString().startsWith("manifests-"))
                    .forEach(file -> restoreSandbox(sessionId, file));
        } catch (IOException e) {
            log.warn("读取多 Agent 沙箱状态失败: session={}", sessionId, e);
        }
        restoreTasks(sessionId, dir.resolve("tasks.json"));
        restoreArbitrations(sessionId, dir.resolve("arbitrations.json"));
        restoreDependencies(sessionId, dir.resolve("dependencies.json"));
        restoreWorkspaceRevision(sessionId, dir.resolve("workspace.json"));
        try (var files = Files.list(dir)) {
            List<Path> entries = files.toList();
            entries.stream().filter(file -> file.getFileName().toString().startsWith("manifests-"))
                    .forEach(file -> restoreManifests(sessionId, file));
            entries.stream().filter(file -> file.getFileName().toString().startsWith("verification-"))
                    .forEach(file -> restoreVerification(sessionId, file));
        } catch (IOException e) {
            log.warn("读取多 Agent 校验状态失败: session={}", sessionId, e);
        }
    }

    private void restoreManifests(String sessionId, Path file) {
        try {
            com.pilot.core.multi.ArtifactManifest[] payloads = mapper.readValue(Files.readString(file),
                    com.pilot.core.multi.ArtifactManifest[].class);
            String name = file.getFileName().toString();
            String sandboxId = name.substring("manifests-".length(), name.length() - ".json".length());
            if (payloads != null && payloads.length > 0) {
                manifests.put(sessionId + "/" + sandboxId, new CopyOnWriteArrayList<>(List.of(payloads)));
            }
        } catch (Exception e) {
            log.warn("忽略损坏的产物清单文件: {}", file, e);
        }
    }

    private void restoreDependencies(String sessionId, Path file) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            Map<String, List<String>> payload = mapper.readValue(Files.readString(file),
                    new tools.jackson.core.type.TypeReference<Map<String, List<String>>>() { });
            if (payload == null) {
                return;
            }
            payload.forEach((sandboxId, upstream) -> {
                if (upstream != null && !upstream.isEmpty()) {
                    dependencies.put(sessionId + "/" + sandboxId, new java.util.LinkedHashSet<>(upstream));
                }
            });
        } catch (Exception e) {
            log.warn("忽略损坏的依赖关系文件: {}", file, e);
        }
    }

    private void restoreSandbox(String sessionId, Path file) {
        try {
            SandboxPayload payload = mapper.readValue(Files.readString(file), SandboxPayload.class);
            if (payload == null || payload.sandboxId() == null) return;
            DiffSandbox sandbox = new DiffSandbox(payload.sandboxId(), payload.agentId(), payload.taskId(), payload.baseCommit());
            if (payload.changes() != null) sandbox.replaceAll(payload.changes());
            List<DiffSandbox> restored = sandboxes.computeIfAbsent(sessionId, key -> new CopyOnWriteArrayList<>());
            if (restored.stream().noneMatch(existing -> existing.id().equals(sandbox.id()))) restored.add(sandbox);
        } catch (Exception e) {
            log.warn("忽略损坏的沙箱状态文件: {}", file, e);
        }
    }

    private void restoreTasks(String sessionId, Path file) {
        if (!Files.isRegularFile(file)) return;
        try {
            TaskPayload[] payloads = mapper.readValue(Files.readString(file), TaskPayload[].class);
            List<TaskView> views = new ArrayList<>();
            if (payloads != null) for (TaskPayload payload : payloads) {
                views.add(new TaskView(payload.taskId(), payload.title(), payload.agentId(), payload.status(),
                        payload.detail(), payload.files()));
            }
            tasks.put(sessionId, List.copyOf(views));
        } catch (Exception e) {
            log.warn("忽略损坏的任务状态文件: {}", file, e);
        }
    }

    private void restoreArbitrations(String sessionId, Path file) {
        if (!Files.isRegularFile(file)) return;
        try {
            Arbitration[] payloads = mapper.readValue(Files.readString(file), Arbitration[].class);
            arbitrations.put(sessionId, new CopyOnWriteArrayList<>(payloads == null ? List.of() : List.of(payloads)));
        } catch (Exception e) {
            log.warn("忽略损坏的仲裁状态文件: {}", file, e);
        }
    }

    private void restoreVerification(String sessionId, Path file) {
        try {
            VerificationPayload payload = mapper.readValue(Files.readString(file), VerificationPayload.class);
            String name = file.getFileName().toString();
            String sandboxId = name.substring("verification-".length(), name.length() - ".json".length());
            DiffSandbox sandbox = sandboxes.getOrDefault(sessionId, List.of()).stream()
                    .filter(box -> box.id().equals(sandboxId)).findFirst().orElse(null);
            if (sandbox == null) {
                return;
            }
            String environment = payload.environmentFingerprint() == null || payload.environmentFingerprint().isBlank()
                    ? environmentFingerprint() : payload.environmentFingerprint();
            com.pilot.core.multi.ArtifactManifest current = manifest(sessionId, sandboxId, environment);
            if (current.candidateHash().equals(payload.candidateHash())
                    && current.dependencyHashes().equals(parseDependencies(payload.dependencyHash()))) {
                verifications.put(sessionId + "/" + sandboxId,
                        new VerificationGate.Report(payload.status(), payload.checks(), payload.detail()));
            }
        } catch (Exception e) {
            log.warn("忽略损坏的校验状态文件: {}", file, e);
        }
    }

    private VerificationPayload readVerification(String sessionId, String sandboxId) {
        Path file = dataDir.file("multi", sanitize(sessionId), "verification-" + sanitize(sandboxId) + ".json");
        if (!Files.isRegularFile(file)) return null;
        try {
            return mapper.readValue(Files.readString(file), VerificationPayload.class);
        } catch (Exception e) {
            log.warn("忽略损坏的校验状态文件: {}", file, e);
            return null;
        }
    }

    private static String validationHash(String candidateHash, String environmentFingerprint, List<String> checks) {
        String canonical = candidateHash + "\n" + (environmentFingerprint == null ? "" : environmentFingerprint)
                + "\n" + (checks == null ? "" : String.join("\n", checks));
        return DiffSandbox.fingerprint(canonical);
    }

    private static List<String> parseDependencies(String packed) {
        if (packed == null || packed.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(packed.split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .sorted()
                .toList();
    }

    /** 校验结论可复现的环境标识：操作系统、架构、JVM 版本。 */
    public static String environmentFingerprint() {
        return System.getProperty("os.name", "") + "|" + System.getProperty("os.arch", "")
                + "|java" + Runtime.version().feature();
    }

    private static String sanitize(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private Path sessionDir(String sessionId) {
        return dataDir.file("multi", sanitize(sessionId));
    }

    private static void atomicWrite(Path file, String content) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
