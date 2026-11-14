package com.pilot.server.mobile;

import java.io.IOException;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/** 设备身份由已认证的连接桥注入；维护接口不在移动代理白名单内。 */
@RestController
@RequiredArgsConstructor
public class MobileReceiptController {
    private final MobileReceiptStore receipts;

    @GetMapping("/api/mobile/receipts/token")
    public ResponseEntity<?> token(@RequestHeader("X-Pilot-Device-Id") String peer) throws IOException {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
            .body(Map.of("requestId", receipts.issue(peer), "receiptVersion", 3));
    }

    @PostMapping("/api/mobile/receipts/ack")
    public Map<String, Boolean> acknowledge(@RequestHeader("X-Pilot-Device-Id") String peer,
        @RequestBody Acknowledgment request) throws IOException {
        receipts.acknowledge(peer, request.requestId());
        return Map.of("acknowledged", true);
    }

    @GetMapping("/api/maintenance/mobile-receipts")
    public MobileReceiptStore.Status status() throws IOException {
        return receipts.status();
    }

    @PostMapping("/api/maintenance/mobile-receipts/cleanup")
    public MobileReceiptStore.Status cleanup() throws IOException {
        return receipts.cleanup();
    }

    @ExceptionHandler(MobileReceiptStore.Rejected.class)
    public ResponseEntity<?> rejected(MobileReceiptStore.Rejected failure) {
        return ResponseEntity.status(failure.status())
            .body(Map.of("message", failure.getMessage(), "code", failure.code(), "uncertain", failure.uncertain()));
    }

    public record Acknowledgment(String requestId) {
    }
}
