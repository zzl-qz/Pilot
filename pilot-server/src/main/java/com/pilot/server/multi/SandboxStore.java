package com.pilot.server.multi;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.pilot.core.multi.ArtifactDiff;
import com.pilot.core.multi.ArtifactFile;
import com.pilot.core.multi.ArtifactManifest;
import com.pilot.core.multi.ChangeStatus;
import com.pilot.core.multi.DiffSandbox;
import com.pilot.core.multi.SandboxChange;
import com.pilot.core.multi.TaskStatus;
import com.pilot.core.util.FileTrees;
import com.pilot.core.workspace.PathValidator;
import com.pilot.server.config.PilotDataDir;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 会话级的沙箱与任务看板存储。
 *
 * <p>沙箱、任务与仲裁状态原子持久化为 JSON，内存只作进程缓存（{@code .pilot/multi/<session>/}），
 * 持久化或恢复失败会阻止操作；重启不会自动重放命令。
 */
@Component
public class SandboxStore {
    private static final Logger log = LoggerFactory.getLogger(SandboxStore.class);
    /**
     * 重启后读到这些状态的任务已经没有执行者，一律改记为取消。
     * TODO(待确认)：ready、verifying 不在 TaskStatus 里，看起来是旧版本写入的值，暂时原样保留。
     */
    private static final Set<String> ABANDONED_AFTER_RESTART =
        Set.of(TaskStatus.PENDING.wireName(), TaskStatus.RUNNING.wireName(), "ready", "verifying");

    /** 界面展示用的改动视图：不含完整文件内容，避免大文件把 SSE / JSON 拖垮。 */
    public record DiffView(String sandboxId, String agentId, String taskId, String path, String diff, int added,
        int removed, boolean existed, String risk, String status) {
    }

    /** 任务卡片。 */
    public record TaskView(String taskId, String title, String agentId, String status, String detail, int files) {
    }

    /** 待仲裁事项。 */
    public record Arbitration(String id, String kind, String taskId, List<String> options, String report) {
    }

    /** 界面展示用的清单摘要（不含文件内容）。 */
    public record ArtifactView(String sandboxId, String taskId, int revision, String candidateHash,
        String environmentFingerprint, List<String> dependencyHashes, int fileCount, Instant createdAt) {
        static ArtifactView of(ArtifactManifest manifest) {
            return new ArtifactView(manifest.sandboxId(), manifest.taskId(), manifest.revision(),
                manifest.candidateHash(), manifest.environmentFingerprint(), manifest.dependencyHashes(),
                manifest.files().size(), manifest.createdAt());
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
    private final ConcurrentMap<String, List<ArtifactManifest>> manifests = new ConcurrentHashMap<>();
    /** 下游沙箱 → 上游沙箱列表，用于依赖变更传播。 */
    private final ConcurrentMap<String, LinkedHashSet<String>> dependencies = new ConcurrentHashMap<>();
    /** 会话 → 工作区版本；沙箱被接受一次就递增，用来让其余沙箱的旧校验失效。 */
    private final ConcurrentMap<String, Long> workspaceRevisions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Map<String, String>> decisions = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final PilotDataDir dataDir;
    private final WorkspaceApplyJournal applyJournal;
    private final Set<String> restoredSessions = ConcurrentHashMap.newKeySet();

    private record SandboxPayload(String sandboxId, String agentId, String taskId, String baseCommit,
        List<SandboxChange> changes, String workspace) {
    }

    private record TaskPayload(String taskId, String title, String agentId, String status, String detail, int files) {
    }

    /**
     * @param workspaceRevision 记录校验时所处的工作区版本；任一沙箱被接受后工作区基线就变了，
     *                          其余沙箱的旧结论随即失效（「选择一部分改动接受」必须重新校验）
     */
    private record VerificationPayload(String candidateHash, String dependencyHash, String environmentFingerprint,
        String validationHash, String status, List<String> checks, String detail, long workspaceRevision) {
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SandboxStore(ObjectMapper mapper, PilotDataDir dataDir) {
        this(mapper, dataDir, new WorkspaceApplyJournal(dataDir.file("apply-journal"), mapper));
    }

    SandboxStore(ObjectMapper mapper, PilotDataDir dataDir, WorkspaceApplyJournal applyJournal) {
        this.mapper = mapper;
        this.dataDir = dataDir;
        this.applyJournal = applyJournal;
    }

    public synchronized void reset(String sessionId) {
        applyJournal.assertSessionIdle(sessionId);
        clearCached(sessionId);
        restoredSessions.remove(sessionId);
        Path dir = sessionDir(sessionId);
        if (Files.isDirectory(dir)) {
            try {
                FileTrees.deleteIfExists(dir);
            } catch (IOException e) {
                throw new UncheckedIOException("清理多 Agent 会话状态失败", e);
            }
        }
    }

    /** 记录最近一次硬校验结果，供界面说明「为什么不能接受」。 */
    public synchronized void recordVerification(String sessionId, String sandboxId, VerificationGate.Report report) {
        recordVerification(sessionId, sandboxId, environmentFingerprint(), report);
    }

    /**
     * 记录校验结果，并把它绑定到「当前候选清单 + 依赖清单 + 环境」。
     *
     * <p>任何一项变化都会让旧结果失效：候选内容变了说明验的不是这份代码；
     * 上游依赖变了说明下游是拿旧接口验的；环境变了（工具链、命令）说明结论不可复现。
     */
    public synchronized void recordVerification(String sessionId, String sandboxId, String environmentFingerprint,
        VerificationGate.Report report) {
        if (report == null) {
            return;
        }
        DiffSandbox sandbox = find(sessionId, sandboxId).orElse(null);
        if (sandbox == null) {
            return;
        }
        ArtifactManifest manifest = manifest(sessionId, sandboxId, environmentFingerprint);
        verifications.put(sessionId + "/" + sandboxId, report);
        persistVerification(sessionId, sandboxId, manifest, report);
        recordManifest(sessionId, manifest);
        // 上游产物迭代后，依赖它的下游校验结果随即失效
        invalidateDownstream(sessionId, sandboxId);
    }

    /** 校验结果只能绑定实际校验过的候选，不能在版本变化后写给新提案。 */
    public synchronized void recordCandidateVerification(String sessionId, String sandboxId, String expected,
        VerificationGate.Report report) {
        DiffSandbox box = find(sessionId, sandboxId).orElseThrow();
        synchronized (box) {
            assertCandidate(sessionId, sandboxId, expected);
            recordVerification(sessionId, sandboxId, report);
        }
    }

    /** 没有跑过校验、或校验针对的已经不是当前产物时返回 null。 */
    public synchronized VerificationGate.Report verification(String sessionId, String sandboxId) {
        VerificationState state = verificationState(sessionId, sandboxId);
        return state.current() ? verifications.get(sessionId + "/" + sandboxId) : null;
    }

    /** 校验为什么可用/不可用：界面和接受接口都要能说清楚。 */
    public synchronized VerificationState verificationState(String sessionId, String sandboxId) {
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
        String environment =
            payload.environmentFingerprint() == null ? environmentFingerprint() : payload.environmentFingerprint();
        // 先判工作区：接受过别的改动后，这份结论所对照的工作区已经不是现在的了
        if (payload.workspaceRevision() != workspaceRevision(sessionId)) {
            verifications.remove(sessionId + "/" + sandboxId);
            return new VerificationState(payload.status(), false, "", "工作区已被其他已接受的变更修改，需要重新校验");
        }
        ArtifactManifest manifest = manifest(sessionId, sandboxId, environment);
        // 先判依赖：候选指纹本身也包含依赖，先判候选会把「上游变了」误报成「自己变了」
        if (!manifest.dependencyHashes()
            .equals(payload.dependencyHash() == null ? List.of() : parseDependencies(payload.dependencyHash()))) {
            verifications.remove(sessionId + "/" + sandboxId);
            return new VerificationState(payload.status(), false, manifest.candidateHash(), "上游依赖产物已变化，需要重新校验");
        }
        if (!manifest.candidateHash().equals(payload.candidateHash())) {
            verifications.remove(sessionId + "/" + sandboxId);
            return new VerificationState(payload.status(), false, manifest.candidateHash(), "候选内容已变化，旧校验结果不再适用");
        }
        return new VerificationState(payload.status(), true, manifest.candidateHash(), "");
    }

    /**
     * 当前候选清单。依赖取自登记的依赖关系，环境指纹默认用当前 JVM 与操作系统标识。
     */
    public synchronized ArtifactManifest manifest(String sessionId, String sandboxId, String environmentFingerprint) {
        DiffSandbox sandbox =
            find(sessionId, sandboxId).orElseThrow(() -> new IllegalArgumentException("没有这个沙箱: " + sandboxId));
        List<ArtifactFile> files = sandbox.changes().stream()
            .map(change -> new ArtifactFile(change.path(), change.baseHash(),
                DiffSandbox.fingerprint(change.newContent()),
                change.newContent() == null ? 0 : change.newContent().getBytes(StandardCharsets.UTF_8).length))
            .toList();
        List<ArtifactManifest> upstream = upstreamManifests(sessionId, sandboxId);
        List<String> dependencyHashes = upstream.stream().map(ArtifactManifest::candidateHash).toList();
        List<String> dependencyManifests = upstream.stream().map(ArtifactManifest::manifestId).toList();
        return ArtifactManifest.create(sessionId, sandboxId, sandbox.taskId(), sandbox.baseCommit(),
            environmentFingerprint, files, dependencyManifests, dependencyHashes, revisionOf(sessionId, sandboxId) + 1,
            Instant.now());
    }

    /** 登记下游沙箱依赖哪些上游沙箱；上游候选变化时下游校验自动失效。 */
    public synchronized void linkDependency(String sessionId, String upstreamSandboxId, String downstreamSandboxId) {
        if (upstreamSandboxId == null || downstreamSandboxId == null || upstreamSandboxId.isBlank()
            || downstreamSandboxId.isBlank()) {
            return;
        }
        dependencies.computeIfAbsent(sessionId + "/" + downstreamSandboxId, key -> new LinkedHashSet<>())
            .add(upstreamSandboxId);
        persistDependencies(sessionId);
        invalidateDownstream(sessionId, upstreamSandboxId);
    }

    /**
     * 按任务依赖登记沙箱依赖：{@code dependsOn} 里的任务若已有沙箱，就把它们记为上游。
     * 同一任务有多个沙箱（重试、多方案）时全部登记，任一上游变化都会让下游重新校验。
     */
    public synchronized void linkTaskDependencies(String sessionId, List<String> dependsOn,
        String downstreamSandboxId) {
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

    public synchronized List<String> dependenciesOf(String sessionId, String sandboxId) {
        restore(sessionId);
        return List.copyOf(dependencies.getOrDefault(sessionId + "/" + sandboxId, new LinkedHashSet<>()));
    }

    public synchronized List<ArtifactView> manifestHistory(String sessionId, String sandboxId) {
        restore(sessionId);
        List<ArtifactManifest> history = manifests.getOrDefault(sessionId + "/" + sandboxId, List.of());
        return history.stream().map(ArtifactView::of).toList();
    }

    /** 最近两版的差异；只有一版时返回空差异。 */
    public synchronized ArtifactDiff manifestDiff(String sessionId, String sandboxId) {
        restore(sessionId);
        List<ArtifactManifest> history = manifests.getOrDefault(sessionId + "/" + sandboxId, List.of());
        if (history.size() < 2) {
            return manifest(sessionId, sandboxId, environmentFingerprint())
                .diff(history.isEmpty() ? null : history.getLast());
        }
        return history.getLast().diff(history.get(history.size() - 2));
    }

    private List<ArtifactManifest> upstreamManifests(String sessionId, String sandboxId) {
        List<ArtifactManifest> result = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        collectUpstream(sessionId, sandboxId, visited, result);
        return result;
    }

    private void collectUpstream(String sessionId, String sandboxId, Set<String> visited,
        List<ArtifactManifest> collected) {
        for (String upstreamId : dependencies.getOrDefault(sessionId + "/" + sandboxId, new LinkedHashSet<>())) {
            if (!visited.add(upstreamId)) {
                continue;
            }
            DiffSandbox upstream = find(sessionId, upstreamId).orElse(null);
            if (upstream == null) {
                continue;
            }
            ArtifactManifest manifest = latestManifest(sessionId, upstreamId)
                .orElseGet(() -> manifest(sessionId, upstreamId, environmentFingerprint()));
            collected.add(manifest);
            collectUpstream(sessionId, upstreamId, visited, collected);
        }
    }

    private Optional<ArtifactManifest> latestManifest(String sessionId, String sandboxId) {
        List<ArtifactManifest> history = manifests.getOrDefault(sessionId + "/" + sandboxId, List.of());
        return history.isEmpty() ? Optional.empty() : Optional.of(history.getLast());
    }

    private int revisionOf(String sessionId, String sandboxId) {
        return manifests.getOrDefault(sessionId + "/" + sandboxId, List.of()).size();
    }

    /** 上游变了：下游的旧校验结果立即失效，并记录新修订供界面比较。 */
    private void invalidateDownstream(String sessionId, String upstreamSandboxId) {
        List<String> downstream = dependencies.entrySet().stream()
            .filter(entry -> entry.getKey().startsWith(sessionId + "/") && entry.getValue().contains(upstreamSandboxId))
            .map(entry -> entry.getKey().substring(sessionId.length() + 1)).toList();
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

    private void recordManifest(String sessionId, ArtifactManifest manifest) {
        String key = sessionId + "/" + manifest.sandboxId();
        List<ArtifactManifest> history = manifests.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>());
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
    public synchronized long workspaceRevision(String sessionId) {
        restore(sessionId);
        return workspaceRevisions.getOrDefault(sessionId, 0L);
    }

    /** 有变更真正写入工作区后调用；返回递增后的版本。 */
    public synchronized long bumpWorkspaceRevision(String sessionId) {
        restore(sessionId);
        long next = workspaceRevisions.merge(sessionId, 1L, Long::sum);
        persistWorkspaceRevision(sessionId);
        return next;
    }

    private void persistWorkspaceRevision(String sessionId) {
        Path file = dataDir.file("multi", sanitize(sessionId), "workspace.json");
        try {
            Files.createDirectories(file.getParent());
            atomicWrite(file,
                mapper.writeValueAsString(Map.of("workspaceRevision", workspaceRevisions.getOrDefault(sessionId, 0L))));
        } catch (IOException e) {
            restoredSessions.remove(sessionId);
            throw new UncheckedIOException("多 Agent 状态持久化失败", e);
        }
    }

    private void restoreWorkspaceRevision(String sessionId, Path file) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            Map<String, Object> payload =
                mapper.readValue(Files.readString(file), new TypeReference<Map<String, Object>>() {});
            Object value = payload == null ? null : payload.get("workspaceRevision");
            if (!(value instanceof Number number) || number.longValue() < 0
                || number.doubleValue() != number.longValue()) {
                throw new IllegalArgumentException("工作区版本无效");
            }
            workspaceRevisions.put(sessionId, number.longValue());
        } catch (Exception e) {
            throw new IllegalStateException("多 Agent 状态损坏，拒绝继续操作: " + file.getFileName(), e);
        }
    }

    public synchronized void add(String sessionId, DiffSandbox sandbox) {
        restore(sessionId);
        persist(sessionId, sandbox);
        List<DiffSandbox> boxes = sandboxes.computeIfAbsent(sessionId, key -> new CopyOnWriteArrayList<>());
        boxes.removeIf(existing -> existing.id().equals(sandbox.id()));
        boxes.add(sandbox);
        discardCheckpoint(sessionId, sandbox.id());
    }

    /** 执行中的提案只写检查点，不能被接受接口看作已完成产物。 */
    public synchronized void checkpoint(String sessionId, DiffSandbox sandbox) {
        restore(sessionId);
        Path file = sessionDir(sessionId).resolve("checkpoints").resolve(sanitize(sandbox.id()) + ".json");
        try {
            Files.createDirectories(file.getParent());
            atomicWrite(file,
                mapper.writeValueAsString(
                    new SandboxPayload(sandbox.id(), sandbox.agentId(), sandbox.taskId(), sandbox.baseCommit(),
                        sandbox.changes(), sandbox.workspace() == null ? null : sandbox.workspace().toString())));
        } catch (IOException e) {
            throw new UncheckedIOException("保存执行检查点失败", e);
        }
    }

    public synchronized void discardCheckpoint(String sessionId, String sandboxId) {
        try {
            Files.deleteIfExists(sessionDir(sessionId).resolve("checkpoints").resolve(sanitize(sandboxId) + ".json"));
        } catch (IOException e) {
            throw new UncheckedIOException("清理执行检查点失败", e);
        }
    }

    public synchronized List<DiffSandbox> sandboxes(String sessionId) {
        restore(sessionId);
        return List.copyOf(sandboxes.getOrDefault(sessionId, List.of()));
    }

    public synchronized Optional<DiffSandbox> find(String sessionId, String sandboxId) {
        return sandboxes(sessionId).stream().filter(box -> box.id().equals(sandboxId)).findFirst();
    }

    /** 某次调度涉及的沙箱（按沙箱 id 前缀关联）。 */
    public synchronized List<DiffSandbox> sandboxesOf(String sessionId, List<String> sandboxIds) {
        return sandboxes(sessionId).stream().filter(box -> sandboxIds.contains(box.id())).toList();
    }

    public synchronized List<DiffView> diffs(String sessionId) {
        List<DiffView> views = new ArrayList<>();
        for (DiffSandbox sandbox : sandboxes(sessionId)) {
            for (SandboxChange change : sandbox.changes()) {
                views.add(new DiffView(sandbox.id(), change.agentId(), change.taskId(), change.path(), change.diff(),
                    change.added(), change.removed(), change.existed(), change.risk().wireName(),
                    change.status().wireName()));
            }
        }
        return List.copyOf(views);
    }

    public synchronized void recordTasks(String sessionId, List<TaskView> views) {
        restore(sessionId);
        persistTasks(sessionId, views);
        tasks.put(sessionId, List.copyOf(views));
    }

    public synchronized List<TaskView> tasks(String sessionId) {
        restore(sessionId);
        return List.copyOf(tasks.getOrDefault(sessionId, List.of()));
    }

    public synchronized void raise(String sessionId, Arbitration arbitration) {
        restore(sessionId);
        validateArbitration(arbitration);
        List<Arbitration> current = arbitrations.getOrDefault(sessionId, List.of());
        Arbitration previous =
            current.stream().filter(item -> item.id().equals(arbitration.id())).findFirst().orElse(null);
        if (previous != null) {
            if (!previous.equals(arbitration)) {
                throw new IllegalStateException("仲裁标识已存在，内容不一致");
            }
            return;
        }
        List<Arbitration> updated = new ArrayList<>(current);
        updated.add(arbitration);
        persistArbitrations(sessionId, updated);
        arbitrations.put(sessionId, new CopyOnWriteArrayList<>(updated));
    }

    public synchronized List<Arbitration> arbitrations(String sessionId) {
        restore(sessionId);
        return arbitrations.getOrDefault(sessionId, List.of()).stream()
            .filter(item -> !decisions.getOrDefault(sessionId, Map.of()).containsKey(item.id())).toList();
    }

    private static void validateArbitration(Arbitration arbitration) {
        if (arbitration == null || arbitration.id() == null || arbitration.id().isBlank()
            || arbitration.options() == null
            || arbitration.options().stream().anyMatch(option -> option == null || option.isBlank())) {
            throw new IllegalArgumentException("仲裁内容无效");
        }
    }

    /** 同一决定可以重放；更改既有决定必须重新发起仲裁，避免晚到回执覆盖。 */
    public synchronized boolean resolve(String sessionId, String arbitrationId, String choice) {
        restore(sessionId);
        Arbitration item =
            arbitrations.getOrDefault(sessionId, List.of()).stream().filter(value -> value.id().equals(arbitrationId))
                .findFirst().orElseThrow(() -> new NoSuchElementException("没有这个仲裁: " + arbitrationId));
        if (choice == null || choice.isBlank() || (!item.options().isEmpty() && !item.options().contains(choice))) {
            throw new IllegalArgumentException("请选择仲裁中的有效选项");
        }
        Map<String, String> updated = new LinkedHashMap<>(decisions.getOrDefault(sessionId, Map.of()));
        String previous = updated.putIfAbsent(arbitrationId, choice);
        if (previous != null) {
            if (!previous.equals(choice)) {
                throw new IllegalStateException("此仲裁已有不同决定，请重新发起仲裁");
            }
            return false;
        }
        Path file = sessionDir(sessionId).resolve("decisions.json");
        try {
            Files.createDirectories(file.getParent());
            atomicWrite(file, mapper.writeValueAsString(updated));
        } catch (IOException e) {
            throw new UncheckedIOException("保存仲裁决定失败", e);
        }
        decisions.put(sessionId, Map.copyOf(updated));
        return true;
    }

    public record Candidate(String baseCommit, String reviewHash, List<SandboxChange> changes) {
    }

    public synchronized Candidate candidate(String sessionId, String sandboxId) {
        DiffSandbox box = find(sessionId, sandboxId).orElseThrow(() -> new IllegalArgumentException("没有这个沙箱"));
        synchronized (box) {
            return new Candidate(box.baseCommit(), box.candidateHash(), box.changes());
        }
    }

    public synchronized <T> T withCandidate(String sessionId, String sandboxId, Supplier<T> action) {
        DiffSandbox box = find(sessionId, sandboxId).orElseThrow();
        synchronized (box) {
            return action.get();
        }
    }

    public synchronized void assertCandidate(String sessionId, String sandboxId, String expected) {
        if (!candidate(sessionId, sandboxId).reviewHash().equals(expected)) {
            throw new IllegalStateException("候选版本已变化，请刷新改动并重新审阅");
        }
    }

    public <T> T withWorkspaceLock(Path workspace, Supplier<T> action) {
        return applyJournal.locked(workspace, action);
    }

    public WorkspaceApplyJournal.State applyState(Path workspace) {
        return applyJournal.state(workspace);
    }

    /** 仅在用户重新操作时恢复文件应用，不重放任何模型或命令。 */
    public void recoverApply(String sessionId, String sandboxId, Path workspace) {
        withWorkspaceLock(workspace, () -> {
            synchronized (this) {
                WorkspaceApplyJournal.Journal journal = applyJournal.read(workspace);
                if (journal == null) {
                    return null;
                }
                if (!journal.sessionId().equals(sessionId) || !journal.sandboxId().equals(sandboxId)) {
                    throw new IllegalStateException("工作区有其他任务未完成的应用，请先处理：" + journal.sandboxId());
                }
                try {
                    if (journal.phase().equals(WorkspaceApplyJournal.PHASE_APPLIED)) {
                        finishApply(sessionId, sandboxId, workspace, journal);
                    } else {
                        applyJournal.rollback(workspace, journal);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException("恢复失败，备份已保留：" + e.getMessage(), e);
                }
                return null;
            }
        });
    }

    public ApplyReport accept(String sessionId, String sandboxId, Path workspace) {
        return accept(sessionId, sandboxId, workspace, null);
    }

    public ApplyReport accept(String sessionId, String sandboxId, Path workspace, String expectedHash) {
        return withWorkspaceLock(workspace, () -> {
            synchronized (this) {
                recoverApply(sessionId, sandboxId, workspace);
                DiffSandbox box = find(sessionId, sandboxId).orElse(null);
                if (box == null) {
                    return new ApplyReport(false, List.of(), List.of(), List.of(), "没有这个沙箱");
                }
                synchronized (box) {
                    if (expectedHash != null) {
                        assertCandidate(sessionId, sandboxId, expectedHash);
                    }
                    List<SandboxChange> pending =
                        box.changes().stream().filter(change -> change.status() == ChangeStatus.PENDING).toList();
                    List<String> skipped = box.changes().stream()
                        .filter(change -> change.status() != ChangeStatus.PENDING).map(SandboxChange::path).toList();
                    if (pending.isEmpty()) {
                        return new ApplyReport(true, List.of(), skipped, List.of(), "没有待接受的改动");
                    }
                    try {
                        var journal = applyJournal.apply(workspace, sessionId, sandboxId, pending,
                            workspaceRevision(sessionId) + 1);
                        finishApply(sessionId, sandboxId, workspace, journal);
                        return new ApplyReport(true, pending.stream().map(SandboxChange::path).toList(), skipped,
                            List.of(), "整批变更已接受");
                    } catch (IOException | RuntimeException e) {
                        return new ApplyReport(false, List.of(), skipped, List.of(e.getMessage()),
                            "应用未完成：" + e.getMessage());
                    }
                }
            }
        });
    }

    private void finishApply(String sessionId, String sandboxId, Path workspace, WorkspaceApplyJournal.Journal journal)
        throws IOException {
        DiffSandbox box = find(sessionId, sandboxId).orElseThrow(() -> new IllegalStateException("恢复的沙箱不存在，保留日志等待处理"));
        synchronized (box) {
            Path root = new PathValidator(workspace).root();
            List<SandboxChange> current = box.changes();
            for (var entry : journal.entries()) {
                if (current.stream()
                    .noneMatch(change -> change.path().equals(entry.path()) && change.newContent().equals(entry.after())
                        && (change.status() == ChangeStatus.PENDING || change.status() == ChangeStatus.ACCEPTED))) {
                    throw new IOException("候选内容与已写日志不一致：" + entry.path());
                }
                if (!Objects.equals(WorkspaceApplyJournal.content(WorkspaceApplyJournal.target(root, entry.path())),
                    entry.after())) {
                    throw new IOException("已应用文件包含后续修改，保留现场：" + entry.path());
                }
            }
            Set<String> applied =
                journal.entries().stream().map(WorkspaceApplyJournal.Entry::path).collect(Collectors.toSet());
            // 版本号记录在写前日志中；崩溃在两次元数据落盘之间时重试也只递增一次。
            workspaceRevisions.put(sessionId, Math.max(workspaceRevision(sessionId), journal.targetRevision()));
            persistWorkspaceRevision(sessionId);
            applyJournal.checkpoint("after-revision");
            if (current.stream()
                .anyMatch(change -> applied.contains(change.path()) && change.status() == ChangeStatus.PENDING)) {
                box.replaceAll(current.stream()
                    .map(change -> applied.contains(change.path()) ? change.withStatus(ChangeStatus.ACCEPTED) : change)
                    .toList());
                persist(sessionId, box);
            }
            applyJournal.checkpoint("after-metadata");
            applyJournal.clear(workspace);
        }
    }

    /** 拒绝某个沙箱：只改状态，不碰工作区。 */
    public synchronized boolean reject(String sessionId, String sandboxId) {
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
    public synchronized void markAccepted(String sessionId, String sandboxId) {
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
            payload.put("workspace", sandbox.workspace() == null ? null : sandbox.workspace().toString());
            atomicWrite(dir.resolve(sanitize(sandbox.id()) + ".json"), mapper.writeValueAsString(payload));
        } catch (IOException e) {
            restoredSessions.remove(sessionId);
            throw new UncheckedIOException("多 Agent 状态持久化失败", e);
        }
    }

    private void persistTasks(String sessionId, List<TaskView> views) {
        Path file = dataDir.file("multi", sanitize(sessionId), "tasks.json");
        try {
            Files.createDirectories(file.getParent());
            atomicWrite(file, mapper.writeValueAsString(views.stream().map(view -> new TaskPayload(view.taskId(),
                view.title(), view.agentId(), view.status(), view.detail(), view.files())).toList()));
        } catch (IOException e) {
            restoredSessions.remove(sessionId);
            throw new UncheckedIOException("多 Agent 状态持久化失败", e);
        }
    }

    private void persistVerification(String sessionId, String sandboxId, ArtifactManifest manifest,
        VerificationGate.Report report) {
        Path file = dataDir.file("multi", sanitize(sessionId), "verification-" + sanitize(sandboxId) + ".json");
        try {
            Files.createDirectories(file.getParent());
            atomicWrite(file,
                mapper.writeValueAsString(new VerificationPayload(manifest.candidateHash(),
                    String.join(",", manifest.dependencyHashes()), manifest.environmentFingerprint(),
                    validationHash(manifest.candidateHash(), manifest.environmentFingerprint(), report.checks()),
                    report.status(), report.checks(), report.detail(), workspaceRevision(sessionId))));
        } catch (IOException e) {
            restoredSessions.remove(sessionId);
            throw new UncheckedIOException("多 Agent 状态持久化失败", e);
        }
    }

    private void persistManifests(String sessionId, String sandboxId) {
        Path file = dataDir.file("multi", sanitize(sessionId), "manifests-" + sanitize(sandboxId) + ".json");
        try {
            Files.createDirectories(file.getParent());
            atomicWrite(file,
                mapper.writeValueAsString(manifests.getOrDefault(sessionId + "/" + sandboxId, List.of())));
        } catch (IOException e) {
            restoredSessions.remove(sessionId);
            throw new UncheckedIOException("多 Agent 状态持久化失败", e);
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
            restoredSessions.remove(sessionId);
            throw new UncheckedIOException("多 Agent 状态持久化失败", e);
        }
    }

    private void persistArbitrations(String sessionId, List<Arbitration> updated) {
        Path file = dataDir.file("multi", sanitize(sessionId), "arbitrations.json");
        try {
            Files.createDirectories(file.getParent());
            atomicWrite(file, mapper.writeValueAsString(updated));
        } catch (IOException e) {
            restoredSessions.remove(sessionId);
            throw new UncheckedIOException("多 Agent 状态持久化失败", e);
        }
    }

    private void restore(String sessionId) {
        Path dir = sessionDir(sessionId);
        if (!restoredSessions.add(sessionId)) {
            return;
        }
        clearCached(sessionId);
        try {
            restoreFiles(sessionId, dir);
        } catch (RuntimeException e) {
            restoredSessions.remove(sessionId);
            clearCached(sessionId);
            throw e;
        }
    }

    private void clearCached(String sessionId) {
        sandboxes.remove(sessionId);
        tasks.remove(sessionId);
        arbitrations.remove(sessionId);
        decisions.remove(sessionId);
        workspaceRevisions.remove(sessionId);
        String prefix = sessionId + "/";
        verifications.keySet().removeIf(key -> key.startsWith(prefix));
        manifests.keySet().removeIf(key -> key.startsWith(prefix));
        dependencies.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private void restoreFiles(String sessionId, Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.list(dir)) {
            files.filter(file -> file.getFileName().toString().endsWith(".json"))
                .filter(file -> !file.getFileName().toString().equals("tasks.json"))
                .filter(file -> !file.getFileName().toString().equals("decisions.json"))
                .filter(file -> !file.getFileName().toString().equals("arbitrations.json"))
                .filter(file -> !file.getFileName().toString().equals("dependencies.json"))
                .filter(file -> !file.getFileName().toString().equals("workspace.json"))
                .filter(file -> !file.getFileName().toString().startsWith("verification-"))
                .filter(file -> !file.getFileName().toString().startsWith("manifests-"))
                .forEach(file -> restoreSandbox(sessionId, file));
        } catch (IOException e) {
            throw new UncheckedIOException("读取多 Agent 沙箱状态失败", e);
        }
        Path decisionsFile = dir.resolve("decisions.json");
        if (Files.isRegularFile(decisionsFile)) {
            try {
                decisions.put(sessionId, Map.copyOf(
                    mapper.readValue(Files.readString(decisionsFile), new TypeReference<Map<String, String>>() {})));
            } catch (Exception e) {
                throw new IllegalStateException("仲裁决定损坏，拒绝继续操作", e);
            }
        }
        Path checkpoints = dir.resolve("checkpoints");
        if (Files.isDirectory(checkpoints)) {
            try (var files = Files.list(checkpoints)) {
                for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                    restoreSandbox(sessionId, file);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("读取执行检查点失败", e);
            }
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
            throw new UncheckedIOException("读取多 Agent 校验状态失败", e);
        }
    }

    private void restoreManifests(String sessionId, Path file) {
        try {
            ArtifactManifest[] payloads = mapper.readValue(Files.readString(file), ArtifactManifest[].class);
            String name = file.getFileName().toString();
            String sandboxId = name.substring("manifests-".length(), name.length() - ".json".length());
            if (payloads == null) {
                throw new IllegalArgumentException("产物清单为空值");
            }
            if (payloads.length > 0) {
                manifests.put(sessionId + "/" + sandboxId, new CopyOnWriteArrayList<>(List.of(payloads)));
            }
        } catch (Exception e) {
            throw new IllegalStateException("多 Agent 状态损坏，拒绝继续操作: " + file.getFileName(), e);
        }
    }

    private void restoreDependencies(String sessionId, Path file) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            Map<String, List<String>> payload =
                mapper.readValue(Files.readString(file), new TypeReference<Map<String, List<String>>>() {});
            if (payload == null) {
                throw new IllegalArgumentException("依赖关系为空值");
            }
            payload.forEach((sandboxId, upstream) -> {
                if (upstream != null && !upstream.isEmpty()) {
                    dependencies.put(sessionId + "/" + sandboxId, new LinkedHashSet<>(upstream));
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException("多 Agent 状态损坏，拒绝继续操作: " + file.getFileName(), e);
        }
    }

    private void restoreSandbox(String sessionId, Path file) {
        try {
            SandboxPayload payload = mapper.readValue(Files.readString(file), SandboxPayload.class);
            if (payload == null || payload.sandboxId() == null || payload.changes() == null) {
                throw new IllegalArgumentException("沙箱内容无效");
            }
            sanitize(payload.sandboxId());
            Path workspace = payload.workspace() == null ? null : Path.of(payload.workspace());
            DiffSandbox sandbox = new DiffSandbox(payload.sandboxId(), payload.agentId(), payload.taskId(),
                payload.baseCommit(), workspace != null && Files.isDirectory(workspace) ? workspace : null);
            if (payload.changes() != null) {
                sandbox.replaceAll(payload.changes());
            }
            List<DiffSandbox> restored = sandboxes.computeIfAbsent(sessionId, key -> new CopyOnWriteArrayList<>());
            if (restored.stream().noneMatch(existing -> existing.id().equals(sandbox.id()))) {
                restored.add(sandbox);
            }
        } catch (Exception e) {
            throw new IllegalStateException("多 Agent 状态损坏，拒绝继续操作: " + file.getFileName(), e);
        }
    }

    private void restoreTasks(String sessionId, Path file) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            TaskPayload[] payloads = mapper.readValue(Files.readString(file), TaskPayload[].class);
            if (payloads == null) {
                throw new IllegalArgumentException("任务列表为空值");
            }
            List<TaskView> views = new ArrayList<>();
            if (payloads != null) {
                for (TaskPayload payload : payloads) {
                    boolean abandoned = payload.status() != null
                        && ABANDONED_AFTER_RESTART.contains(payload.status().toLowerCase(Locale.ROOT));
                    views.add(new TaskView(payload.taskId(), payload.title(), payload.agentId(),
                        abandoned ? TaskStatus.CANCELLED.wireName() : payload.status(),
                        abandoned ? "服务已重启，原执行者失效；保留产物，请重新派发任务" : payload.detail(), payload.files()));
                }
            }
            persistTasks(sessionId, views);
            tasks.put(sessionId, List.copyOf(views));
        } catch (Exception e) {
            throw new IllegalStateException("多 Agent 状态损坏，拒绝继续操作: " + file.getFileName(), e);
        }
    }

    private void restoreArbitrations(String sessionId, Path file) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            Arbitration[] payloads = mapper.readValue(Files.readString(file), Arbitration[].class);
            if (payloads == null) {
                throw new IllegalArgumentException("仲裁列表为空值");
            }
            for (Arbitration item : payloads) {
                validateArbitration(item);
            }
            arbitrations.put(sessionId, new CopyOnWriteArrayList<>(List.of(payloads)));
        } catch (Exception e) {
            throw new IllegalStateException("多 Agent 状态损坏，拒绝继续操作: " + file.getFileName(), e);
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
            ArtifactManifest current = manifest(sessionId, sandboxId, environment);
            if (current.candidateHash().equals(payload.candidateHash())
                && current.dependencyHashes().equals(parseDependencies(payload.dependencyHash()))) {
                verifications.put(sessionId + "/" + sandboxId,
                    new VerificationGate.Report(payload.status(), payload.checks(), payload.detail()));
            }
        } catch (Exception e) {
            throw new IllegalStateException("多 Agent 状态损坏，拒绝继续操作: " + file.getFileName(), e);
        }
    }

    private VerificationPayload readVerification(String sessionId, String sandboxId) {
        Path file = dataDir.file("multi", sanitize(sessionId), "verification-" + sanitize(sandboxId) + ".json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return mapper.readValue(Files.readString(file), VerificationPayload.class);
        } catch (Exception e) {
            throw new IllegalStateException("多 Agent 状态损坏，拒绝继续操作: " + file.getFileName(), e);
        }
    }

    private static String validationHash(String candidateHash, String environmentFingerprint, List<String> checks) {
        String canonical = candidateHash + "\n" + (environmentFingerprint == null ? "" : environmentFingerprint) + "\n"
            + (checks == null ? "" : String.join("\n", checks));
        return DiffSandbox.fingerprint(canonical);
    }

    private static List<String> parseDependencies(String packed) {
        if (packed == null || packed.isBlank()) {
            return List.of();
        }
        return Arrays.stream(packed.split(",")).map(String::strip).filter(value -> !value.isEmpty()).sorted().toList();
    }

    /** 校验结论可复现的环境标识：操作系统、架构、JVM 版本。 */
    public static String environmentFingerprint() {
        return System.getProperty("os.name", "") + "|" + System.getProperty("os.arch", "") + "|java"
            + Runtime.version().feature();
    }

    private static String sanitize(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-][A-Za-z0-9._-]{0,199}")) {
            throw new IllegalArgumentException("多 Agent 状态标识无效");
        }
        return value;
    }

    private Path sessionDir(String sessionId) {
        return dataDir.file("multi", sanitize(sessionId));
    }

    private static void atomicWrite(Path file, String content) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                var bytes = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
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
