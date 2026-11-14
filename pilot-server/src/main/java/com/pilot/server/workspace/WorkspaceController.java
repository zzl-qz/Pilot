package com.pilot.server.workspace;

import java.io.UncheckedIOException;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.core.workspace.WorkspaceViolationException;

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

    /** 未发送首条消息时也能引用文件，不为补全创建空会话。 */
    @GetMapping("/files/flat")
    public SessionFilesController.FlatResponse files(@RequestParam("path") String path,
        @RequestParam(name = "limit", required = false) Integer limit) {
        try {
            return SessionFilesController.flatWorkspace(workspaces.requireDirectory(path), limit);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/files")
    public SessionFilesController.TreeResponse tree(@RequestParam("root") String root,
        @RequestParam(name = "path", defaultValue = "") String path) {
        try {
            return SessionFilesController.treeWorkspace(workspaces.requireDirectory(root), path);
        } catch (IllegalArgumentException | WorkspaceViolationException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/file")
    public SessionFilesController.FileResponse file(@RequestParam("root") String root,
        @RequestParam("path") String path) {
        try {
            return SessionFilesController.fileWorkspace(workspaces.requireDirectory(root), path);
        } catch (IllegalArgumentException | WorkspaceViolationException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
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
