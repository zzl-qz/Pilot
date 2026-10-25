package com.pilot.server.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/approve")
public class ApprovalController {
    @PostMapping
    public ApprovalReply approve(@RequestBody ApprovalRequest request) {
        return new ApprovalReply(request.callId(), request.approved());
    }

    public record ApprovalRequest(String callId, boolean approved) { }
    public record ApprovalReply(String callId, boolean approved) { }
}
