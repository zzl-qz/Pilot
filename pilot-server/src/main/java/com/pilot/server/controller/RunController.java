package com.pilot.server.controller;

import com.pilot.server.session.RunStarter;
import com.pilot.server.workspace.RunSnapshotService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 运行控制：取消执行与回滚本次改动。 */
@RestController
@RequestMapping("/api/runs")
public class RunController {
    private final RunStarter runStarter;
    private final RunSnapshotService snapshots;

    public RunController(RunStarter runStarter, RunSnapshotService snapshots) {
        this.runStarter = runStarter;
        this.snapshots = snapshots;
    }

    @PostMapping("/{runId}/cancel")
    public CancelResponse cancel(@PathVariable String runId) {
        if (!runStarter.cancel(runId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "run not found or already finished: " + runId);
        }
        return new CancelResponse(runId, true);
    }

    /** 把该次运行改过的文件还原成运行前的内容，并删除本次新建的文件。 */
    @PostMapping("/{runId}/rollback")
    public RunSnapshotService.RollbackReport rollback(@PathVariable String runId) {
        try {
            return snapshots.rollback(runId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    public record CancelResponse(String runId, boolean accepted) {
    }
}
