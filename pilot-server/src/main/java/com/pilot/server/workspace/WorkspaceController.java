package com.pilot.server.workspace;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.core.workspace.WorkspaceViolationException;

import lombok.RequiredArgsConstructor;

/** 本地目录浏览：让用户在新建会话时选择工作区，而不是依赖服务端固定的 workspace。 */
@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {
    private final WorkspaceService workspaces;

    @GetMapping("/roots")
    public List<WorkspaceService.RootEntry> roots() {
        return workspaces.roots();
    }

    /** 未发送首条消息时也能引用文件，不为补全创建空会话。 */
    @GetMapping("/files/flat")
    public SessionFilesController.FlatResponse files(@RequestParam("path") String path,
        @RequestParam(name = "limit", required = false) Integer limit) {
        try {
            Path root = workspaces.requireRegisteredWorkspace(path);
            workspaces.requireSafeWorkspaceFile(root, ".");
            return SessionFilesController.flatWorkspace(root, limit);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/files")
    public SessionFilesController.TreeResponse tree(@RequestParam("root") String root,
        @RequestParam(name = "path", defaultValue = "") String path) {
        try {
            Path workspace = workspaces.requireRegisteredWorkspace(root);
            workspaces.requireSafeWorkspaceFile(workspace, path == null || path.isBlank() ? "." : path);
            return SessionFilesController.treeWorkspace(workspace, path);
        } catch (IllegalArgumentException | WorkspaceViolationException e) {
            throw invalidPath(e);
        }
    }

    @GetMapping("/file")
    public SessionFilesController.FileResponse file(@RequestParam("root") String root,
        @RequestParam("path") String path) {
        try {
            Path workspace = workspaces.requireRegisteredWorkspace(root);
            workspaces.requireSafeWorkspaceFile(workspace, path);
            return SessionFilesController.fileWorkspace(workspace, path);
        } catch (IllegalArgumentException | WorkspaceViolationException e) {
            throw invalidPath(e);
        }
    }

    @GetMapping("/file/raw")
    public ResponseEntity<Resource> raw(@RequestParam("root") String root, @RequestParam("path") String path,
        @RequestParam(name = "download", defaultValue = "false") boolean download) {
        try {
            Path workspace = workspaces.requireRegisteredWorkspace(root);
            workspaces.requireSafeWorkspaceFile(workspace, path);
            return SessionFilesController.rawWorkspace(workspace, path, download);
        } catch (IllegalArgumentException | WorkspaceViolationException e) {
            throw invalidPath(e);
        }
    }

    @PostMapping("/file/open")
    public UniversalFilePreviewService.OpenResult open(@RequestBody WorkspaceOpenRequest request) {
        if (request == null || request.root() == null || request.root().isBlank() || request.path() == null
            || request.path().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少工作区或文件路径");
        }
        try {
            Path workspace = workspaces.requireRegisteredWorkspace(request.root());
            workspaces.requireSafeWorkspaceFile(workspace, request.path());
            return SessionFilesController.openWorkspace(workspace, request.path(), request.mode());
        } catch (IllegalArgumentException | WorkspaceViolationException e) {
            throw invalidPath(e);
        }
    }

    private static ResponseStatusException invalidPath(RuntimeException error) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
    }

    public record WorkspaceOpenRequest(String root, String path, String mode) {
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
