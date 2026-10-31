package com.pilot.server.controller;

import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.server.workspace.GitWorktrees;
import com.pilot.server.workspace.WorkspaceService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;

/**
 * 平行检出：人选定分支后另开一个 worktree 对话，做完再把未提交改动交回原来的目录。
 */
@RestController
@RequestMapping("/api/worktrees")
public class WorktreeController {
    private final GitWorktrees worktrees;
    private final SessionService sessions;
    private final WorkspaceService workspaces;

    public WorktreeController(GitWorktrees worktrees, SessionService sessions, WorkspaceService workspaces) {
        this.worktrees = worktrees;
        this.sessions = sessions;
        this.workspaces = workspaces;
    }

    @PostMapping
    public Opened create(@RequestBody CreateRequest request) {
        if (request == null || request.workspace() == null || request.workspace().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workspace 不能为空");
        }
        Path workspace;
        try {
            workspace = workspaces.requireDirectory(request.workspace());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        GitWorktrees.Created created;
        try {
            created = worktrees.create(workspace, request.branch());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        String title = request.title() == null || request.title().isBlank()
                ? "worktree " + created.branch() : request.title().strip();
        String projectId = request.projectId() == null || request.projectId().isBlank() ? null : request.projectId();
        Session session = sessions.createSession(title, created.path(), projectId, false);
        return new Opened(session.id(), session.title(), created.path(), created.base(), created.branch(), created.commit());
    }

    @GetMapping
    public Info info(@RequestParam("path") String path) {
        return worktrees.findByPath(Path.of(path)).map(meta -> new Info(true, meta.id(), meta.path(),
                meta.base(), meta.branch(), meta.commit())).orElseGet(() -> new Info(false, "", "", "", "", ""));
    }

    @PostMapping("/handoff")
    public Handed handoff(@RequestParam("sessionId") String sessionId) {
        Session session = sessions.find(sessionId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        GitWorktrees.Handoff handed;
        try {
            handed = worktrees.handoff(Path.of(session.workspace()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        sessions.moveToWorkspace(sessionId, handed.base());
        return new Handed(sessionId, handed.base(), handed.files(), handed.note());
    }

    @DeleteMapping
    public void remove(@RequestParam("path") String path) {
        try {
            worktrees.remove(Path.of(path));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    public record CreateRequest(String workspace, String branch, String title, String projectId) {
    }

    public record Opened(String sessionId, String title, String path, String base, String branch, String commit) {
    }

    public record Info(boolean worktree, String id, String path, String base, String branch, String commit) {
    }

    public record Handed(String sessionId, String workspace, int files, String note) {
    }
}
