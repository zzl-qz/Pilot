package com.pilot.server.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.core.session.SessionService;
import com.pilot.server.session.RunStarter;
import com.pilot.server.session.TaskControlService;

import tools.jackson.databind.node.NullNode;

/** 用户可随时查看任务依据，空闲时编辑约束和验收证据。 */
@RestController
@RequestMapping("/api/sessions/{id}/task")
public class TaskControlController {
    private final SessionService sessions;
    private final RunStarter runs;
    private final TaskControlService tasks;

    public TaskControlController(SessionService sessions, RunStarter runs, TaskControlService tasks) {
        this.sessions = sessions;
        this.runs = runs;
        this.tasks = tasks;
    }

    @GetMapping
    public Object get(@PathVariable String id) {
        requireSession(id);
        TaskControlService.TaskContract task = tasks.load(id);
        return task == null ? NullNode.getInstance() : task;
    }

    @PutMapping
    public TaskControlService.TaskContract update(@PathVariable String id,
        @RequestBody TaskControlService.Requirements request) {
        requireSession(id);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写任务约束和验收条件");
        }
        String slot = runs.claimRun(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "任务正在运行，请用立即纠正调整方向，或暂停后编辑约束和验收条件"));
        boolean completedTransition;
        try {
            TaskControlService.TaskContract before = tasks.load(id);
            TaskControlService.TaskContract updated = tasks.update(id, request.constraints(), request.acceptance());
            completedTransition =
                "COMPLETED".equals(updated.status()) && (before == null || !"COMPLETED".equals(before.status()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } finally {
            runs.abandon(slot);
        }
        // 编辑占位释放后才允许队列接棒，否则下一件事永远抢不到运行槽。
        if (completedTransition) {
            runs.requirementsUpdated(id);
        }
        return tasks.load(id);
    }

    private void requireSession(String id) {
        if (sessions.find(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found: " + id);
        }
    }
}
