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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** 接受/拒绝变更的处理结果。 */
    public record ApplyReport(boolean ok, List<String> written, List<String> skipped, List<String> failed,
                              String message) {
    }

    private final ConcurrentMap<String, List<DiffSandbox>> sandboxes = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<TaskView>> tasks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<Arbitration>> arbitrations = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final PilotDataDir dataDir;

    public SandboxStore(ObjectMapper mapper, PilotDataDir dataDir) {
        this.mapper = mapper;
        this.dataDir = dataDir;
    }

    public void reset(String sessionId) {
        sandboxes.remove(sessionId);
        tasks.remove(sessionId);
        arbitrations.remove(sessionId);
    }

    public void add(String sessionId, DiffSandbox sandbox) {
        sandboxes.computeIfAbsent(sessionId, key -> new CopyOnWriteArrayList<>()).add(sandbox);
        persist(sessionId, sandbox);
    }

    public List<DiffSandbox> sandboxes(String sessionId) {
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
    }

    public List<TaskView> tasks(String sessionId) {
        return List.copyOf(tasks.getOrDefault(sessionId, List.of()));
    }

    public void raise(String sessionId, Arbitration arbitration) {
        arbitrations.computeIfAbsent(sessionId, key -> new CopyOnWriteArrayList<>()).add(arbitration);
    }

    public List<Arbitration> arbitrations(String sessionId) {
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
        Path dir = dataDir.file("multi", sanitize(sessionId));
        try {
            Files.createDirectories(dir);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sandboxId", sandbox.id());
            payload.put("agentId", sandbox.agentId());
            payload.put("taskId", sandbox.taskId());
            payload.put("baseCommit", sandbox.baseCommit());
            payload.put("changes", sandbox.changes());
            Files.writeString(dir.resolve(sandbox.id() + ".json"), mapper.writeValueAsString(payload));
        } catch (IOException e) {
            // 审计副本失败不影响主流程，但要在日志里留痕
            log.warn("写入沙箱审计副本失败: session={}, sandbox={}", sessionId, sandbox.id(), e);
        }
    }

    private static String sanitize(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
