package com.pilot.server.session;

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
}
