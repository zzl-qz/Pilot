package com.pilot.server.tool;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.core.session.SessionService;
import com.pilot.core.tool.command.CommandTaskManager;
import com.pilot.server.session.lookup.SessionLookup;

import lombok.RequiredArgsConstructor;

/** 命令的运行状态独立于模型回合；查询和停止只能操作当前会话所属的任务。 */
@RestController
@RequestMapping("/api/sessions/{id}/commands")
@RequiredArgsConstructor
public class CommandTaskController {
    private final SessionService sessions;
    private final CommandTaskManager commands;

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
        @RequestParam(defaultValue = "0") long offset, @RequestParam(defaultValue = "16000") int limit) {
        requireSession(id);
        if (offset < 0 || limit < 1 || limit > 65536) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日志位置不能小于 0，单次读取长度必须在 1–65536 之间");
        }
        CommandTaskManager.LogChunk chunk = commands.logs(id, taskId, offset, limit);
        if (chunk == null) {
            throw missingTask();
        }
        return chunk;
    }

    @PostMapping("/{taskId}/stop")
    public CommandTaskManager.Task stop(@PathVariable String id, @PathVariable String taskId) {
        requireSession(id);
        return requireTask(commands.stop(id, taskId));
    }

    /** 页面上的输入框走这里向 interactive 启动的命令写 stdin；空 text 且不结束输入视为无意义请求。 */
    @PostMapping("/{taskId}/input")
    public CommandTaskManager.Task input(@PathVariable String id, @PathVariable String taskId,
        @RequestParam(defaultValue = "") String text, @RequestParam(defaultValue = "false") boolean endInput) {
        requireSession(id);
        if (text.isEmpty() && !endInput) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text 和 endInput 至少给一个");
        }
        try {
            return requireTask(commands.input(id, taskId, text, endInput, null));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            // 已结束或没有打开的输入通道：请求本身合法，与当前任务状态冲突。
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    private void requireSession(String id) {
        SessionLookup.require(sessions, id);
    }

    private static CommandTaskManager.Task requireTask(CommandTaskManager.Task task) {
        if (task == null) {
            throw missingTask();
        }
        return task;
    }

    private static ResponseStatusException missingTask() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "没有该命令任务，或它不属于当前会话");
    }

    public record Tasks(List<CommandTaskManager.Task> tasks) {
    }
}
