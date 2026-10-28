package com.pilot.server.controller;

import com.pilot.server.session.RunStarter;
import com.pilot.server.workspace.ChangeService;
import com.pilot.server.workspace.RunSnapshotService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.List;

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

    /**
     * 本轮运行改了哪些文件：只统计该次运行写过的路径，带增删行数与逐文件 diff。
     *
     * <p>没有快照（本轮没写过文件，或运行早于快照功能）时返回空清单，而不是 404，
     * 客户端可以统一按「本次没有改动」渲染。
     */
    @GetMapping("/{runId}/changes")
    public ChangeService.RunChangeReport changes(@PathVariable String runId) {
        RunSnapshotService.SnapshotMeta meta = snapshots.meta(runId);
        if (meta == null) {
            return new ChangeService.RunChangeReport(runId, null, false, List.of(), 0, 0, false, null);
        }
        try {
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
        if (meta == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有该运行的回滚快照: " + runId);
        String operationId = "restore_" + java.util.UUID.randomUUID();
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
