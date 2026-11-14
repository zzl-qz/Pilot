package com.pilot.server.mobile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.ObjectMapper;

/** Web/桌面提交凭据。复用受备份保护的持久回执，查询永远不会调用原写操作。 */
@RestController
public class SubmissionController {
    private final MobileReceiptStore receipts;
    private final ObjectMapper mapper;

    public SubmissionController(MobileReceiptStore receipts, ObjectMapper mapper) {
        this.receipts = receipts;
        this.mapper = mapper;
    }

    @GetMapping("/api/submissions/token")
    public ResponseEntity<?> token() throws IOException {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String peer = HexFormat.of().formatHex(bytes);
        return ResponseEntity.ok().header("Cache-Control", "no-store")
            .body(Map.of("requestId", peer + ":" + receipts.issue(peer)));
    }

    @GetMapping("/api/submissions/result")
    public ResponseEntity<?> result(@RequestHeader("X-Pilot-Request-Id") String id) throws IOException {
        MobileReceiptStore.Receipt receipt = receipts.lookup(id);
        if (receipt == null || receipt.status() == 0) {
            // 未收到、仍在传输、执行中或中途崩溃均不能当作明确失败。
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("state", "unknown"));
        }
        var body = mapper.readTree(new String(Base64.getDecoder().decode(receipt.body()), StandardCharsets.UTF_8));
        boolean unknown = receipt.status() >= 500 || body.path("uncertain").asBoolean(false);
        String state = unknown ? "unknown" : receipt.status() < 300 ? "accepted" : "rejected";
        return ResponseEntity.ok().header("Cache-Control", "no-store")
            .body(Map.of("state", state, "status", receipt.status(), "response", body));
    }

    @PostMapping("/api/submissions/ack")
    public Map<String, Boolean> acknowledge(@RequestBody Acknowledgment request) throws IOException {
        receipts.acknowledgeRequest(request.requestId());
        return Map.of("acknowledged", true);
    }

    @ExceptionHandler(MobileReceiptStore.Rejected.class)
    public ResponseEntity<?> rejected(MobileReceiptStore.Rejected failure) {
        return ResponseEntity.status(failure.status())
            .body(Map.of("message", failure.getMessage(), "uncertain", failure.uncertain()));
    }

    public record Acknowledgment(String requestId) {
    }
}
