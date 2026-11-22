package com.pilot.server.multi;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 工作区看板接口：按工作区一次返回所有相关会话的状态。
 *
 * <p>只读；写操作仍只走各会话自己的沙箱接受入口，这里不提供任何改文件的通道。
 */
@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceOverviewController {
    private final WorkspaceOverviewService overviews;

    public WorkspaceOverviewController(WorkspaceOverviewService overviews) {
        this.overviews = overviews;
    }

    @GetMapping("/overview")
    public WorkspaceOverviewService.Overview overview(@RequestParam String path) {
        try {
            return overviews.overview(path);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }
}
