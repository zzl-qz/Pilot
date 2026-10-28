package com.pilot.server.controller;

import com.pilot.core.session.SessionService;
import com.pilot.core.tool.CommandTaskManager;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** 命令的运行状态独立于模型回合；查询和停止只能操作当前会话所属的任务。 */
@RestController
@RequestMapping("/api/sessions/{id}/commands")
public class CommandTaskController {
    private final SessionService sessions;
    private final CommandTaskManager commands;

    public CommandTaskController(SessionService sessions, CommandTaskManager commands) {
        this.sessions = sessions;
        this.commands = commands;
    }

    @GetMapping
    public Tasks list(@PathVariable String id) {
        requireSession(id);
        return new Tasks(commands.list(id));
    }

    @GetMapping("/{taskId}")
    public CommandTaskManager.Task status(@PathVariable String id, @PathVariable String taskId) {
        requireSession(id);
        return requireTask(commands.get(id, taskId));
    }

    @GetMapping("/{taskId}/logs")
    public CommandTaskManager.LogChunk logs(@PathVariable String id, @PathVariable String taskId,
                                           @RequestParam(defaultValue = "0") long offset,
                                           @RequestParam(defaultValue = "16000") int limit) {
        requireSession(id);
        if (offset < 0 || limit < 1 || limit > 65536) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日志位置不能小于 0，单次读取长度必须在 1–65536 之间");
        }
        var chunk = commands.logs(id, taskId, offset, limit);
        if (chunk == null) throw missingTask();
        return chunk;
    }

    @PostMapping("/{taskId}/stop")
    public CommandTaskManager.Task stop(@PathVariable String id, @PathVariable String taskId) {
        requireSession(id);
        return requireTask(commands.stop(id, taskId));
    }

    private void requireSession(String id) {
        if (sessions.find(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found: " + id);
        }
    }

    private static CommandTaskManager.Task requireTask(CommandTaskManager.Task task) {
        if (task == null) throw missingTask();
        return task;
    }

    private static ResponseStatusException missingTask() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "没有该命令任务，或它不属于当前会话");
    }

    public record Tasks(List<CommandTaskManager.Task> tasks) { }
}
