package com.pilot.server.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.core.tool.QuestionBroker;
import com.pilot.server.session.question.QuestionController;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"pilot.store=memory"})
class InteractionControllerTest {
    private static final Path ROOT =
        Path.of(System.getProperty("java.io.tmpdir"), "pilot-interaction-test-" + UUID.randomUUID());

    static {
        try {
            Files.createDirectories(ROOT.resolve("workspace"));
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("pilot.workspace", () -> ROOT.resolve("workspace").toString());
        registry.add("pilot.data.dir", () -> ROOT.resolve("data").toString());
    }

    @Value("${local.server.port}")
    int port;
    @Autowired
    InteractionController controller;
    @Autowired
    InteractionHub hub;
    @Autowired
    ApprovalBroker approvals;
    @Autowired
    QuestionBroker questions;
    @Autowired
    SessionService sessions;
    @Autowired
    QuestionController questionController;

    @Test
    void snapshotAndQuestionSessionOwnershipWork() throws Exception {
        Session owner = sessions.createSession("修复构建脚本");
        ApprovalBroker scoped = ApprovalBroker.forSession(approvals, owner.id());
        var approvalFuture = scoped.register("call_app_1", "run_command", "rm -rf dist", Map.of("command", "rm -rf dist"));
        var questionFuture = questions.register("call_q_1", owner.id(), "使用哪个部署环境", List.of("测试", "生产"),
            Duration.ofMinutes(5));

        try {
            InteractionHub.PendingSnapshot snapshot = controller.pending();
            assertEquals(1, snapshot.approvals().size());
            assertEquals(owner.id(), snapshot.approvals().getFirst().sessionId());
            assertEquals("修复构建脚本", snapshot.approvals().getFirst().sessionTitle());
            assertNotNull(snapshot.approvals().getFirst().expiresAt());

            assertEquals(1, snapshot.questions().size());
            assertEquals(owner.id(), snapshot.questions().getFirst().sessionId());
            assertEquals("修复构建脚本", snapshot.questions().getFirst().sessionTitle());
            assertEquals(List.of("测试", "生产"), questionController.pending().getFirst().options());

            ResponseStatusException mismatch = assertThrows(ResponseStatusException.class,
                () -> questionController.answer("other_session", "call_q_1", Map.of("choice", "测试")));
            assertEquals(HttpStatus.CONFLICT, mismatch.getStatusCode());
            assertFalse(questionFuture.isDone());

            Map<String, String> answered = questionController.answer(owner.id(), "call_q_1", Map.of("choice", "测试"));
            assertEquals("测试", answered.get("choice"));
            assertEquals("测试", questionFuture.get());

            assertTrue(approvals.resolve("call_app_1", true));
            assertTrue(approvalFuture.get());
            assertTrue(controller.pending().approvals().isEmpty());
            assertTrue(controller.pending().questions().isEmpty());
        } finally {
            approvals.resolve("call_app_1", false);
            questions.cancel("call_q_1");
        }
    }

    @Test
    void sseStreamsSnapshotAndRunAttention() throws Exception {
        Session owner = sessions.createSession("部署验证");
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            out.write(("GET /api/interactions/events HTTP/1.1\r\nHost: 127.0.0.1:" + port
                + "\r\nAccept: text/event-stream\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String headerLine;
            boolean okHeader = false;
            while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                if (headerLine.startsWith("HTTP/1.1 200")) {
                    okHeader = true;
                }
            }
            assertTrue(okHeader, "Expected 200 SSE response");

            // 初始快照
            String initial = readUntil(reader, "approvals");
            assertTrue(initial.contains("questions"), initial);

            // 新增选择后推送快照
            var q = questions.register("call_sse_q", owner.id(), "确认回滚吗", List.of("确认回滚", "保持现状"),
                Duration.ofMinutes(3));
            try {
                String qFrame = readUntil(reader, "call_sse_q");
                assertTrue(qFrame.contains("部署验证"), qFrame);
                assertTrue(questions.cancel("call_sse_q"));
                assertEquals(null, q.get());
            } finally {
                questions.cancel("call_sse_q");
            }

            // 完成提醒推送
            hub.publishRunAttention(owner.id(), "run_1", "done", "COMPLETED", null, "全部测试已通过");
            String attnFrame = readUntil(reader, "run.attention");
            assertTrue(attnFrame.contains("run.attention"), attnFrame);
            String payload = readUntil(reader, "全部测试已通过");
            assertTrue(payload.contains("\"kind\":\"done\""), payload);
        }
    }

    @Test
    void approvalEndpointAcceptsOptionalRejectionReasonOverHttp() throws Exception {
        var decision = approvals.register("ses_http", "call_http_reject", "run_command", "npm install",
            Map.of("command", "npm install"));
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/approvals/call_http_reject"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"approved\":false,\"reason\":\"请改用 pnpm\"}"))
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertFalse(decision.get());
            assertEquals("请改用 pnpm", approvals.takeRejectionReason("call_http_reject"));
        }
    }

    private static String readUntil(BufferedReader reader, String needle) throws IOException {
        StringBuilder collected = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            collected.append(line).append('\n');
            if (line.contains(needle)) {
                return collected.toString();
            }
        }
        return collected.toString();
    }
}
