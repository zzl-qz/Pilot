package com.pilot.server.controller;

import com.pilot.server.workspace.WorkspaceService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.UncheckedIOException;
import java.util.List;

/** 本地目录浏览：让用户在新建会话时选择工作区，而不是依赖服务端固定的 workspace。 */
@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {
    private final WorkspaceService workspaces;

    public WorkspaceController(WorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    @GetMapping("/roots")
    public List<WorkspaceService.RootEntry> roots() {
        return workspaces.roots();
    }

    @GetMapping("/browse")
    public WorkspaceService.BrowseResult browse(@RequestParam(name = "path", required = false) String path) {
        try {
            return workspaces.browse(path);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (UncheckedIOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
