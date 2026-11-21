package com.pilot.server.session.notes;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.core.session.SessionService;
import com.pilot.server.common.web.ApiErrors;
import com.pilot.server.common.web.SessionLookup;

import lombok.RequiredArgsConstructor;

/**
 * 会话想法：用户执行任务时记下的临时内容，可增删改查，按会话隔离。
 *
 * <p>接口只负责存取；发送消息、加入执行队列仍然只能由用户主动操作，
 * 保存或修改想法不会产生事件，也不会推动执行。
 */
@RestController
@RequestMapping("/api/sessions/{session}/notes")
@RequiredArgsConstructor
public class SessionNotesController {
    private final SessionNotes notes;
    private final SessionService sessions;

    @GetMapping
    public List<SessionNotes.Note> list(@PathVariable String session) {
        SessionLookup.require(sessions, session);
        return notes.list(session);
    }

    @PostMapping
    public SessionNotes.Note create(@PathVariable String session, @RequestBody(required = false) NoteRequest request) {
        SessionLookup.require(sessions, session);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        return notes.create(session, request.content(),
            request.source() == null ? null : new SessionNotes.SourceInput(request.source().sequence(),
                request.source().excerpt(), request.source().role()));
    }

    @PutMapping("/{noteId}")
    public SessionNotes.Note update(@PathVariable String session, @PathVariable String noteId,
        @RequestBody(required = false) NoteRequest request) {
        SessionLookup.require(sessions, session);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        return notes.update(session, noteId, request.content());
    }

    @DeleteMapping("/{noteId}")
    public ResponseEntity<Void> delete(@PathVariable String session, @PathVariable String noteId) {
        SessionLookup.require(sessions, session);
        notes.delete(session, noteId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 改状态：inbox / planned / done / dropped。
     *
     * <p>状态是用户自己的安排，不产生事件、不推进执行；只有下面的转任务接口会组装正文。
     */
    @PostMapping("/{noteId}/status")
    public SessionNotes.Note status(@PathVariable String session, @PathVariable String noteId,
        @RequestBody(required = false) StatusRequest request) {
        SessionLookup.require(sessions, session);
        if (request == null || request.status() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status 必填");
        }
        SessionNotes.Link link =
            request.link() == null ? null : new SessionNotes.Link(request.link().kind(), request.link().id());
        return notes.setStatus(session, noteId, request.status(), link);
    }

    /**
     * 把想法转成可直接提交的任务正文，并标记为已安排。
     *
     * <p>只返回文本，不代发消息：想法与执行是两件事，提交必须由用户触发（前端交给正常的输入路径，
     * 运行中就进队列、空闲就新开任务）。
     */
    @PostMapping("/{noteId}/promote")
    public SessionNotes.Promotion promote(@PathVariable String session, @PathVariable String noteId,
        @RequestBody(required = false) PromoteRequest request) {
        SessionLookup.require(sessions, session);
        SessionNotes.Link link =
            new SessionNotes.Link(request == null || request.kind() == null ? "session" : request.kind(), session);
        return notes.promote(session, noteId, link);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> bad(RuntimeException e) {
        return ApiErrors.badRequest(e);
    }

    public record NoteRequest(String content, SourceRequest source) {
        public NoteRequest(String content) {
            this(content, null);
        }
    }

    /** 从对话选中文字保存时的来源：消息序号、原文与角色；旧客户端只传 content。 */
    public record SourceRequest(Integer sequence, String excerpt, String role) {
    }

    /** @param status inbox / planned / done / dropped；link 可选，用于记录安排到了哪 */
    public record StatusRequest(String status, LinkRequest link) {
    }

    /** @param kind queue（运行中进队列）或 session（空闲时新开任务） */
    public record PromoteRequest(String kind) {
    }

    public record LinkRequest(String kind, String id) {
    }
}
