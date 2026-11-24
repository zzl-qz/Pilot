package com.pilot.server.workspace;

import java.nio.file.Path;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.session.lookup.SessionLookup;

import lombok.RequiredArgsConstructor;

/** 会话改动审阅：读取该会话工作区的 git 状态与 diff，并按文件撤销改动。 */
@RestController
@RequestMapping("/api/sessions/{id}")
@RequiredArgsConstructor
public class ChangeController {
    private final SessionService sessions;
    private final ChangeService changes;
    private final AgentProperties properties;

    @GetMapping("/diff")
    public ChangeService.ChangeReport diff(@PathVariable String id) {
        return changes.inspect(workspace(id));
    }

    /**
     * 单个文件的补丁：整份 diff 被截断时，界面点开某个文件再按需取。
     *
     * @param path 工作区内的相对路径
     */
    @GetMapping("/diff/file")
    public ChangeService.FilePatch fileDiff(@PathVariable String id, @RequestParam String path) {
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path 不能为空");
        }
        try {
            return changes.fileDiff(workspace(id), path);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * 撤销改动：{@code paths} 里的文件回退到 HEAD（未跟踪的直接删除），{@code all=true} 时撤销全部。
     *
     * <p>与 diff 使用同一套工作区解析，避免「看的是 A 仓库、撤的是 B 仓库」。
     */
    @PostMapping("/diff/restore")
    public ChangeService.RestoreReport restore(@PathVariable String id,
        @RequestBody(required = false) RestoreRequest request) {
        return changes.restore(workspace(id), request == null ? List.of() : request.paths(),
            request != null && Boolean.TRUE.equals(request.all()));
    }

    /** 读取当前工作区近期提交历史与当前分支名。 */
    @GetMapping("/git/log")
    public ChangeService.CommitLogReport gitLog(@PathVariable String id,
        @RequestParam(defaultValue = "15") int limit) {
        return changes.log(workspace(id), limit);
    }

    /** 基于选中改动生成本地中文提交说明草稿（无需模型 Key）。 */
    @PostMapping("/git/commit-message")
    public ChangeService.DraftMessageResult draftCommitMessage(@PathVariable String id,
        @RequestBody(required = false) CommitMessageRequest request) {
        return changes.draftCommitMessage(workspace(id), request == null ? List.of() : request.paths(),
            request == null || Boolean.TRUE.equals(request.all()));
    }

    /** 暂存并提交选中文件或全部改动。 */
    @PostMapping("/git/commit")
    public ChangeService.CommitResult commit(@PathVariable String id,
        @RequestBody(required = false) CommitRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        return changes.commit(workspace(id), request.paths(), Boolean.TRUE.equals(request.all()), request.message());
    }

    @PostMapping("/diff/discard-unstaged")
    public ChangeService.GitOperationResult discardUnstaged(@PathVariable String id,
        @RequestBody(required = false) PathRequest request) {
        return changes.discardUnstaged(workspace(id), request == null ? null : request.path());
    }

    @GetMapping("/git/branches")
    public ChangeService.GitBranchReport branches(@PathVariable String id) {
        return changes.branches(workspace(id));
    }

    @PostMapping("/git/switch")
    public ChangeService.GitOperationResult switchBranch(@PathVariable String id,
        @RequestBody(required = false) BranchRequest request) {
        return changes.switchBranch(workspace(id), request == null ? null : request.branch());
    }

    @GetMapping("/git/stash")
    public ChangeService.GitStashReport stashes(@PathVariable String id) {
        return changes.stashes(workspace(id));
    }

    @PostMapping("/git/stash/push")
    public ChangeService.GitOperationResult stashPush(@PathVariable String id,
        @RequestBody(required = false) StashPushRequest request) {
        return changes.stashPush(workspace(id), request == null ? null : request.message());
    }

    @PostMapping("/git/stash/pop")
    public ChangeService.GitOperationResult stashPop(@PathVariable String id,
        @RequestBody(required = false) StashPopRequest request) {
        return changes.stashPop(workspace(id), request == null ? null : request.reference());
    }

    private Path workspace(String id) {
        Session session = SessionLookup.require(sessions, id);
        String workspace =
            session.workspace() == null || session.workspace().isBlank() ? properties.workspace() : session.workspace();
        return Path.of(workspace);
    }

    /**
     * @param all 为 true 时撤销工作区里的全部改动，忽略 {@code paths}。
     *            用包装类型：缺省字段按 null 反序列化，primitive 会直接 400。
     */
    public record RestoreRequest(List<String> paths, Boolean all) {
    }

    public record CommitMessageRequest(List<String> paths, Boolean all) {
    }

    public record CommitRequest(List<String> paths, Boolean all, String message) {
    }

    public record PathRequest(String path) { }
    public record BranchRequest(String branch) { }
    public record StashPushRequest(String message) { }
    public record StashPopRequest(String reference) { }
}
