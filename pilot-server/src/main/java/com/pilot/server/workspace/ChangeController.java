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
import com.pilot.server.common.web.SessionLookup;
import com.pilot.server.config.AgentProperties;

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
}
