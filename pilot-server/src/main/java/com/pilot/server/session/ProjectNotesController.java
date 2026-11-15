package com.pilot.server.session;

import java.util.List;
import java.util.Objects;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pilot.core.session.SessionService;

import lombok.RequiredArgsConstructor;

/**
 * 项目内想法搜索：只查当前项目下的会话，按关键词匹配想法正文。
 * 结果是即时读取，编辑、删除后重新搜索就会反映最新内容。
 */
@RestController
@RequestMapping("/api/library/notes")
@RequiredArgsConstructor
public class ProjectNotesController {
    private final SessionNotes notes;
    private final SessionService sessions;

    @GetMapping("/search")
    public Result search(@RequestParam(defaultValue = "") String q, @RequestParam(required = false) String projectId) {
        String term = q == null ? "" : q.strip();
        if (term.isEmpty() || term.length() > 200) {
            return new Result(List.of(), false);
        }
        String project = projectId == null ? "" : projectId;
        List<SessionNotes.SessionRef> refs = project.isBlank() ? List.of()
            : sessions.list().stream().filter(session -> Objects.equals(project, session.projectId()))
                .filter(session -> !session.archived())
                .map(session -> new SessionNotes.SessionRef(session.id(), session.title())).toList();
        List<SessionNotes.NoteHit> hits = notes.search(refs, term);
        return new Result(hits, hits.size() >= SessionNotes.SEARCH_LIMIT);
    }

    /** @param hasMore 达到上限时提示缩小关键词，而不是静默截断 */
    public record Result(List<SessionNotes.NoteHit> items, boolean hasMore) {
    }
}
