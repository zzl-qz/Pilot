package com.pilot.server.coordination;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.pilot.core.coordination.CoordinationScopeResolver;
import com.pilot.core.coordination.ProjectCoordinationScope;
import com.pilot.core.coordination.WorkspaceCoordinationEventBus;
import com.pilot.core.coordination.WorkspaceEventDraft;
import com.pilot.core.coordination.WorkspaceEventType;
import com.pilot.core.project.ProjectRepository;
import com.pilot.core.session.SessionRepository;
import com.pilot.server.session.events.SseStreamTransport;

/** 真实 Tomcat/Socket 验证工作区事件流：MockMvc 的输出流不支持 WriteListener。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"pilot.store=memory", "pilot.workspace-events.max-events=4",
        "pilot.workspace-events.send-timeout-ms=800", "pilot.events.send-timeout-ms=800"})
class WorkspaceCoordinationStreamControllerTest {
    private static final Path ROOT =
        Path.of(System.getProperty("java.io.tmpdir"), "pilot-workspace-events-" + UUID.randomUUID());

    static {
        try {
            Files.createDirectories(ROOT.resolve("primary"));
            Files.createDirectories(ROOT.resolve("attached"));
            Files.createDirectories(ROOT.resolve("outside"));
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @Value("${local.server.port}")
    int port;
    @Autowired
    WorkspaceCoordinationEventBus events;
    @Autowired
    SseStreamTransport transport;
    @Autowired
    ProjectRepository projects;
    @Autowired
    SessionRepository sessions;
    @Autowired
    CoordinationScopeResolver scopes;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("pilot.data.dir", () -> ROOT.resolve("data").toString());
    }

    @AfterEach
    void drainsDisconnectedSubscriptions() {
        // 客户端静默断开时容器不会立刻报错，只能靠后续写入或 20s 心跳发现；
        // 这里尽力推一批事件让断开连接尽快进入关闭流程，不把回收时机当成断言条件。
        for (int index = 0; index < 4; index++) {
            publishCleanupEvent(null);
            for (var project : projects.list()) {
                publishCleanupEvent(project.id());
            }
        }
    }

    private void publishCleanupEvent(String projectId) {
        events.publish(new WorkspaceEventDraft(WorkspaceEventType.SANDBOX_CHANGED, projectId, "s-cleanup",
            ROOT.resolve("primary").toString(), "cleanup", Map.of()));
    }

    @Test
    void readyFrameAndLiveEventsKeepPublishOrder() throws Exception {
        Path workspace = ROOT.resolve("primary");
        long base = events.latestSequence();
        try (Socket socket = open("/api/workspace/events?workspace=" + encode(workspace) + "&after=" + base);
            BufferedReader reader = reader(socket)) {
            String ready = readUntil(reader, "earliestSequence");
            assertTrue(ready.contains("200"), ready);
            assertTrue(ready.contains("\"directories\":"), ready);
            assertTrue(ready.contains("event:workspace.ready"), ready);

            long started = events.publish(new WorkspaceEventDraft(WorkspaceEventType.RUN_STARTED, null, "s-alpha",
                workspace.toString(), "alpha-run", Map.of())).sequence();
            long changed = events.publish(new WorkspaceEventDraft(WorkspaceEventType.SANDBOX_CHANGED, null, "s-alpha",
                workspace.toString(), "alpha-sandbox", Map.of())).sequence();
            String frames = readUntil(reader, "alpha-sandbox");

            assertTrue(frames.contains("event:run.started"), frames);
            assertTrue(frames.contains("id:" + started + "\n"), frames);
            assertTrue(frames.contains("id:" + changed + "\n"), frames);
            assertTrue(frames.indexOf("event:run.started") < frames.indexOf("event:sandbox.changed"), frames);
            assertTrue(frames.contains("\"sessionId\":\"s-alpha\""), frames);
        }
    }

    @Test
    void lastEventIdResumesWithoutRepeatingEarlierEvents() throws Exception {
        Path workspace = ROOT.resolve("primary");
        long claimed = events.publish(new WorkspaceEventDraft(WorkspaceEventType.FILE_CLAIMED, null, "s-resume",
            workspace.toString(), "claim-marker", Map.of())).sequence();
        long released = events.publish(new WorkspaceEventDraft(WorkspaceEventType.FILE_RELEASED, null, "s-resume",
            workspace.toString(), "release-marker", Map.of())).sequence();
        try (Socket socket = open("/api/workspace/events?workspace=" + encode(workspace), "Last-Event-ID: " + claimed);
            BufferedReader reader = reader(socket)) {
            String frames = readUntil(reader, "release-marker");

            assertFalse(frames.contains("event:file.claimed"), frames);
            assertTrue(frames.contains("id:" + released + "\n"), frames);
        }
    }

    @Test
    void cursorOlderThanRetainedHistoryReceivesReset() throws Exception {
        Path workspace = ROOT.resolve("primary");
        long base = events.latestSequence();
        long completedBefore = transport.metrics().closed().getOrDefault("complete", 0L);
        for (int index = 0; index < 6; index++) {
            events.publish(draft(WorkspaceEventType.RUN_STARTED, "s-reset", workspace));
        }
        try (Socket socket = open("/api/workspace/events?workspace=" + encode(workspace) + "&after=" + (base + 1));
            BufferedReader reader = reader(socket)) {
            String frames = readUntil(reader, "cursor_expired");

            assertTrue(frames.contains("event:workspace.reset"), frames);
            assertTrue(frames.contains("cursor_expired"), frames);
            assertFalse(frames.contains("event:run.started"), frames);
            // 服务端发完 reset 就结束这条流，连接必须由服务端自己回收。
            await(() -> transport.metrics().closed().getOrDefault("complete", 0L) > completedBefore,
                () -> "reset 后连接没有关闭：" + transport.metrics());
        }
    }

    @Test
    void otherProjectsEventsStayInvisible() throws Exception {
        Path shared = ROOT.resolve("attached");
        projects.create("p-alpha", "Alpha", List.of(shared.toString()), null, Instant.now());
        projects.create("p-beta", "Beta", List.of(shared.toString()), null, Instant.now());
        ProjectCoordinationScope scope = scopes.scopeForWorkspace(shared.toString(), sessions.list());
        String visibleProject = scope.projectId();
        assertNotNull(visibleProject, "入口目录绑定到项目时应解析出项目");
        String hiddenProject = "p-alpha".equals(visibleProject) ? "p-beta" : "p-alpha";
        long base = events.latestSequence();
        events.publish(new WorkspaceEventDraft(WorkspaceEventType.SANDBOX_CHANGED, hiddenProject, "s-hidden",
            shared.toString(), "hidden-change", Map.of()));
        long visible = events.publish(new WorkspaceEventDraft(WorkspaceEventType.SANDBOX_CHANGED, visibleProject,
            "s-visible", shared.toString(), "visible-change", Map.of())).sequence();
        try (Socket socket = open("/api/workspace/events?workspace=" + encode(shared) + "&after=" + base);
            BufferedReader reader = reader(socket)) {
            String frames = readUntil(reader, "visible-change");

            assertTrue(frames.contains("s-visible"), frames);
            assertFalse(frames.contains("s-hidden"), frames);
            assertTrue(frames.contains("id:" + visible + "\n"), frames);
        }
    }

    @Test
    void metricsEndpointReportsBusAndTransport() throws Exception {
        try (Socket socket = open("/api/workspace/events/metrics"); BufferedReader reader = reader(socket)) {
            String body = readFully(reader);

            assertTrue(body.contains("200"), body);
            assertTrue(body.contains("\"published\""), body);
            assertTrue(body.contains("\"transport\""), body);
        }
    }

    private static WorkspaceEventDraft draft(WorkspaceEventType type, String sessionId, Path directory) {
        return WorkspaceEventDraft.of(type, sessionId, directory.toString(), type.wireName());
    }

    private static String encode(Path path) {
        return URLEncoder.encode(path.toString(), StandardCharsets.UTF_8);
    }

    private Socket open(String path, String... headers) throws IOException {
        Socket socket = new Socket();
        socket.setReceiveBufferSize(1024);
        socket.connect(new InetSocketAddress("127.0.0.1", port));
        socket.setSoTimeout(5000);
        StringBuilder request = new StringBuilder().append("GET ").append(path).append(" HTTP/1.1\r\n")
            .append("Host: localhost:").append(port).append("\r\n").append("Connection: close\r\n");
        for (String header : headers) {
            request.append(header).append("\r\n");
        }
        request.append("\r\n");
        socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.UTF_8));
        return socket;
    }

    private static BufferedReader reader(Socket socket) throws IOException {
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
    }

    private static String readUntil(BufferedReader reader, String marker) throws IOException {
        StringBuilder text = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            text.append(line).append('\n');
            if (text.indexOf(marker) >= 0) {
                break;
            }
        }
        return text.toString();
    }

    private static String readFully(BufferedReader reader) throws IOException {
        StringBuilder text = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            text.append(line).append('\n');
        }
        return text.toString();
    }

    private static void await(BooleanSupplier condition) throws Exception {
        await(condition, () -> "条件在 6 秒内没有成立");
    }

    private static void await(BooleanSupplier condition, Supplier<String> message) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(6).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), message);
    }
}
