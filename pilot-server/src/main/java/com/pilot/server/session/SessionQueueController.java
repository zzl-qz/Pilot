package com.pilot.server.session;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.core.session.SessionService;
import com.pilot.server.attachment.AttachmentService;
import com.pilot.server.run.RunStarter;
import com.pilot.server.session.lookup.SessionLookup;

import tools.jackson.databind.JsonNode;

import lombok.RequiredArgsConstructor;

/** 会话排队消息的查询、编辑、排序、暂停与引导接口。 */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionQueueController {
    private final SessionService sessions;
    private final RunStarter runStarter;
    private final AttachmentService attachments;

    /** 还没被当前运行消费的消息。空着表示没有在排队。 */
    @GetMapping("/{id}/queue")
    public QueueResponse queue(@PathVariable String id) {
        SessionLookup.require(sessions, id);
        return queueResponse(id);
    }

    /** 暂停或恢复当前会话的消息队列；暂停不打断正在运行的一轮。 */
    @PostMapping("/{id}/queue/hold")
    public QueueResponse holdQueue(@PathVariable String id, @RequestBody(required = false) JsonNode body) {
        SessionLookup.require(sessions, id);
        boolean held = body != null && body.path("held").asBoolean(false);
        runStarter.setQueueHeld(id, held);
        return queueResponse(id);
    }

    /** 改队列里的某一句，下标从 0 开始。还没轮到的才能改。 */
    @PutMapping("/{id}/queue/{index}")
    public QueueResponse editQueued(@PathVariable String id, @PathVariable int index,
        @RequestBody(required = false) QueueEditRequest request) {
        SessionLookup.require(sessions, id);
        if (request == null || request.content() == null || request.content().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content must not be blank");
        }
        SessionController.requireContentWithinLimit(request.content());
        RunStarter.QueueRequirements requirements = request.constraints() == null && request.acceptance() == null ? null
            : new RunStarter.QueueRequirements(request.constraints(), request.acceptance());
        if (!runStarter.editQueued(id, index, request.content(), requirements, request.revision())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "队列已变化或消息不存在，请刷新后重试");
        }
        return queueResponse(id);
    }

    /** 往前排一位。整条消息（含图片）一起移动，所以不能靠前端互换两条的文字。 */
    @PostMapping("/{id}/queue/{index}/up")
    public QueueResponse moveQueuedUp(@PathVariable String id, @PathVariable int index) {
        SessionLookup.require(sessions, id);
        if (!runStarter.moveQueuedUp(id, index)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "这一句已经在最前面，或已经不在队列里");
        }
        return queueResponse(id);
    }

    /** 拖拽排序：把 index 这一句挪到 to 的位置，整条消息（含图片）一起移动。 */
    @PostMapping("/{id}/queue/{index}/move")
    public QueueResponse moveQueued(@PathVariable String id, @PathVariable int index, @RequestParam int to,
        @RequestParam(required = false) String revision) {
        SessionLookup.require(sessions, id);
        if (!runStarter.moveQueued(id, index, to, revision)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "位置无效，或这一句已经不在队列里");
        }
        return queueResponse(id);
    }

    @DeleteMapping("/{id}/queue/{index}")
    public QueueResponse removeQueued(@PathVariable String id, @PathVariable int index,
        @RequestParam(required = false) String revision) {
        SessionLookup.require(sessions, id);
        if (!runStarter.removeQueued(id, index, revision)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "队列已变化或消息不存在，请刷新后重试");
        }
        return queueResponse(id);
    }

    @PostMapping("/{id}/queue/{index}/steer")
    public QueueResponse steerQueued(@PathVariable String id, @PathVariable int index,
        @RequestBody QueueSteerRequest request) {
        SessionLookup.require(sessions, id);
        if (request == null || request.runId() == null || request.revision() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少运行或队列版本，请刷新后重试");
        }
        if (!runStarter.steerQueued(id, request.runId(), index, request.revision())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务或队列已变化，引导未发送，请查看最新队列");
        }
        return queueResponse(id);
    }

    private QueueResponse queueResponse(String id) {
        List<RunStarter.QueuedMessage> messages = runStarter.queuedMessages(id);
        return new QueueResponse(messages.stream().map(RunStarter.QueuedMessage::content).toList(),
            messages.stream()
                .map(message -> new QueueEntry(message.content(),
                    attachments.displayAttachments(id, message.attachments()), message.imagePositions(),
                    message.requirements() == null || message.requirements().empty() ? null
                        : new QueueRequirementsView(message.requirements().constraints(),
                            message.requirements().acceptance()),
                    message.mode(), message.skill()))
                .toList(),
            RunStarter.queueRevision(messages), runStarter.queueHeld(id), runStarter.queueWaitingReason(id));
    }

    public record QueueSteerRequest(String runId, String revision) {
    }

    public record QueueEditRequest(String content, String revision, List<String> constraints, List<String> acceptance) {
        public QueueEditRequest(String content, String revision) {
            this(content, revision, null, null);
        }
    }

    /**
     * @param items         排队消息正文，顺序即执行顺序（保持旧契约）
     * @param entries       同一批消息的结构化版本，额外带上图片附件和它们在正文里的位置
     * @param held          队列是否被手动暂停
     * @param waitingReason 界面展示的等待原因（手动暂停 / 等待本轮结束 / 等待验收）
     */
    public record QueueResponse(List<String> items, List<QueueEntry> entries, String revision, boolean held,
        String waitingReason) {
        public QueueResponse(List<String> items, List<QueueEntry> entries, String revision) {
            this(items, entries, revision, false, "");
        }
    }

    public record QueueEntry(String content, List<AttachmentService.DisplayAttachment> attachments,
        List<Integer> imagePositions, QueueRequirementsView requirements, String mode, String skill) {
        public QueueEntry(String content, List<AttachmentService.DisplayAttachment> attachments,
            List<Integer> imagePositions) {
            this(content, attachments, imagePositions, null, "", "");
        }

        public QueueEntry(String content, List<AttachmentService.DisplayAttachment> attachments,
            List<Integer> imagePositions, QueueRequirementsView requirements) {
            this(content, attachments, imagePositions, requirements, "", "");
        }
    }

    /** 排队消息自己的约束与验收；null 表示没有额外要求。 */
    public record QueueRequirementsView(List<String> constraints, List<String> acceptance) {
    }
}
