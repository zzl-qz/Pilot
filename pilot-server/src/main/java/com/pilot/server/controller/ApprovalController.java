package com.pilot.server.controller;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.approval.PendingApproval;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** 客户端 → 服务端的审批入口：唤醒在 callId 上阻塞的 Agent 线程。 */
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {
    private final ApprovalBroker approvals;

    public ApprovalController(ApprovalBroker approvals) {
        this.approvals = approvals;
    }

    @PostMapping("/{callId}")
    public ApprovalResponse resolve(@PathVariable String callId, @RequestBody(required = false) ApprovalRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body with approved=true/false is required");
        }
        boolean accepted = approvals.resolve(callId, request.approved());
        if (!accepted) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "no pending approval for callId " + callId + "（可能已超时或已处理）");
        }
        return new ApprovalResponse(callId, request.approved(), true);
    }

    @GetMapping
    public List<PendingApproval> pending() {
        return approvals.pending();
    }

    public record ApprovalRequest(boolean approved) { }

    public record ApprovalResponse(String callId, boolean approved, boolean resolved) { }
}
