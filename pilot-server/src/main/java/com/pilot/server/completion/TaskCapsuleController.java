package com.pilot.server.completion;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pilot.core.session.SessionService;
import com.pilot.server.common.web.SessionLookup;

import lombok.RequiredArgsConstructor;

/**
 * 任务胶囊接口：导出当前任务的可移交状态，或把胶囊导入目标项目。
 *
 * <p>导入只建会话、恢复契约、写入交接说明；不会自动应用来源 diff。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TaskCapsuleController {
    private final SessionService sessions;
    private final TaskCapsuleService capsules;

    @GetMapping("/sessions/{id}/capsule")
    public TaskCapsuleService.Capsule export(@PathVariable String id) {
        SessionLookup.require(sessions, id);
        return capsules.export(id);
    }

    @PostMapping("/projects/{projectId}/capsules")
    public TaskCapsuleService.ImportResult importCapsule(@PathVariable String projectId,
        @RequestParam(name = "workspace", required = false) String workspace,
        @RequestBody TaskCapsuleService.Capsule capsule) {
        return capsules.importCapsule(capsule, projectId, workspace);
    }
}
