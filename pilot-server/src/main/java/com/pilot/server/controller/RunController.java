package com.pilot.server.controller;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.server.session.RunStarter;
import com.pilot.server.workspace.ChangeService;
import com.pilot.server.workspace.RunSnapshotService;

/** 运行控制：取消执行、回滚改动与查看本轮改动。 */
@RestController
@RequestMapping("/api/runs")
public class RunController {
    private final RunStarter runStarter;
    private final RunSnapshotService snapshots;
    private final ChangeService changes;

    public RunController(RunStarter runStarter, RunSnapshotService snapshots, ChangeService changes) {
        this.runStarter = runStarter;
        this.snapshots = snapshots;
        this.changes = changes;
    }

    @PostMapping("/{runId}/cancel")
    public CancelResponse cancel(@PathVariable String runId) {
        if (!runStarter.cancel(runId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "run not found or already finished: " + runId);
        }
        return new CancelResponse(runId, true);
    }

    @PostMapping("/{runId}/pause")
    public CancelResponse pause(@PathVariable String runId) {
        if (!runStarter.pause(runId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务已结束或正在暂停");
        }
        return new CancelResponse(runId, true);
    }

    /**
     * 优先读取运行结束时保存的逐行差异。旧运行沿用原先的工作区查询方式。
     */
    @GetMapping("/{runId}/changes")
    public ChangeService.RunChangeReport changes(@PathVariable String runId) {
        try {
            ChangeService.RunChangeReport report = snapshots.changes(runId);
            if (report != null) {
                return report;
            }
            RunSnapshotService.SnapshotMeta meta = snapshots.meta(runId);
            if (meta == null || !runId.equals(meta.runId())) {
                return new ChangeService.RunChangeReport(runId, null, false, List.of(), 0, 0, false, null);
            }
            return changes.inspectRun(runId, Path.of(meta.workspace()),
                meta.files().stream().map(RunSnapshotService.SnapshotEntry::path).toList());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /** 把该次运行改过的文件还原成运行前的内容，并删除本次新建的文件。 */
    @PostMapping("/{runId}/rollback")
    public RunSnapshotService.RollbackReport rollback(@PathVariable String runId) {
        RunSnapshotService.SnapshotMeta meta = snapshots.meta(runId);
        if (meta == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有该运行的回滚快照: " + runId);
        }
        String operationId = "restore_" + UUID.randomUUID();
        if (!runStarter.lockWorkspace(operationId, Path.of(meta.workspace()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "工作区仍有任务在运行，请结束后再回滚");
        }
        try {
            return snapshots.rollback(runId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        } finally {
            runStarter.abandon(operationId);
        }
    }

    public record CancelResponse(String runId, boolean accepted) {
    }
}
