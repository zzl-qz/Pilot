package com.pilot.server.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.apache.coyote.AbstractProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.pilot.core.event.AssistantDelta;
import com.pilot.core.event.AssistantPreview;
import com.pilot.core.session.SessionService;
import com.pilot.server.common.web.AsyncEventStream;

/** 真实 Tomcat/Socket 验证非阻塞发送，MockMvc 的输出流不支持 WriteListener。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"pilot.store=memory", "pilot.events.send-timeout-ms=800", "pilot.events.queue-wait-ms=5000",
        "pilot.events.queue-bytes=1048576", "pilot.events.reference-bytes=524288",
        "pilot.events.session-connections=64"})
class SessionEventHttpTest {
    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    int port;
    @Autowired
    SessionService sessions;
    @Autowired
    ServletWebServerApplicationContext application;

    @AfterEach
    void releasesSubscriptionsAndServletStreamsBeforeNextTest() throws Exception {
        await(() -> sessions.subscriberCount() == 0);
        await(() -> AsyncEventStream.metrics().connections() == 0);
        var tomcat = (TomcatWebServer)application.getWebServer();
        var protocol = (AbstractProtocol<?>)tomcat.getTomcat().getConnector().getProtocolHandler();
        await(() -> protocol.getWaitingProcessorCount() == 0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String root =
            Path.of(System.getProperty("java.io.tmpdir"), "pilot-stream-http-" + UUID.randomUUID()).toString();
        registry.add("pilot.data.dir", () -> root);
    }

    private Socket open(String path) throws Exception {
        Socket socket = new Socket();
        socket.setReceiveBufferSize(1024);
        socket.connect(new InetSocketAddress("127.0.0.1", port));
        socket.setSoTimeout(5000);
        socket.getOutputStream()
            .write(("GET " + path + " HTTP/1.1\r\nHost: localhost:" + port + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        return socket;
    }

    private String readUntil(Socket socket, String marker) throws Exception {
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
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

    @Test
    void historyAndCursorReplayUseActualHttp() throws Exception {
        String id = sessions.createSession("真实回放").id();
        var run = sessions.sendMessage(id, "你好");
        sessions.publish(id, run.runId(), new AssistantDelta("世界"));
        try (Socket all = open("/api/sessions/" + id + "/events?after=0&revision=0&protocol=2")) {
            String text = readUntil(all, "\"sequence\":2");
            assertTrue(text.contains("200"), text);
            assertTrue(text.contains("event:stream.ready"));
            assertTrue(text.contains("event:run.started"));
            assertTrue(text.contains("event:assistant.delta"));
        }
        try (Socket after = open("/api/sessions/" + id + "/events?after=1&revision=0")) {
            String text = readUntil(after, "\"sequence\":2");
            assertFalse(text.contains("event:run.started"));
            assertTrue(text.contains("event:assistant.delta"));
        } finally {
            sessions.delete(id);
        }
    }

    @Test
    void staleVersionReceivesResetAndLargeEventUsesFetchWithoutAdvancingCursor() throws Exception {
        String id = sessions.createSession("版本与大事件").id();
        sessions.publish(id, "run", new AssistantDelta("长".repeat(100_000)));
        try (Socket socket = open("/api/sessions/" + id + "/events?after=0&revision=0&protocol=2")) {
            String text = readUntil(socket, "\"sequence\":1");
            assertTrue(text.contains("event:stream.event"), text);
            assertFalse(text.contains("id:1\n"));
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:" + port + "/api/sessions/" + id + "/events/1?revision=0"))
                .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("长".repeat(1000)));
        }
        sessions.dropEventsFrom(id, 1);
        try (Socket socket = open("/api/sessions/" + id + "/events?after=1&revision=0")) {
            String text = readUntil(socket, "\"historyRevision\":1");
            assertTrue(text.contains("event:stream.reset"), text);
        } finally {
            sessions.delete(id);
        }
    }

    @Test
    void clientThatNeverReadsIsDisconnectedAndPublisherKeepsRunning() throws Exception {
        String id = sessions.createSession("不读连接").id();
        try (Socket socket = open("/api/sessions/" + id + "/events?after=0")) {
            await(() -> sessions.subscriberCount() > 0);
            long started = System.nanoTime();
            for (int i = 0; i < 800 && sessions.subscriberCount() > 0; i++) {
                sessions.publish(id, "r", new AssistantDelta("x".repeat(60_000)));
                Thread.sleep(2);
            }
            assertTrue(System.nanoTime() - started < Duration.ofSeconds(10).toNanos());
            await(() -> sessions.subscriberCount() == 0);
            await(() -> AsyncEventStream.metrics().connections() == 0);
            assertEquals(0, AsyncEventStream.metrics().queuedBytes());
            assertTrue(AsyncEventStream.metrics().closed().getOrDefault("byte_or_count_limit", 0L)
                + AsyncEventStream.metrics().closed().getOrDefault("send_timeout", 0L) > 0);
        } finally {
            sessions.delete(id);
        }
    }

    @Test
    void stoppedReaderIsClosedByDeadlineEvenWhenQueueIsNotFull() throws Exception {
        String id = sessions.createSession("发送期限").id();
        long before = AsyncEventStream.metrics().closed().getOrDefault("send_timeout", 0L);
        try (Socket socket = open("/api/sessions/" + id + "/events?after=0")) {
            await(() -> sessions.subscriberCount() > 0);
            boolean waiting = false;
            for (int i = 0; i < 300; i++) {
                sessions.publish(id, "r", new AssistantDelta("x".repeat(60_000)));
                Thread.sleep(10);
                if (AsyncEventStream.metrics().queuedBytes() > 0) {
                    Thread.sleep(100);
                    if (AsyncEventStream.metrics().queuedBytes() > 0) {
                        waiting = true;
                        break;
                    }
                }
            }
            assertTrue(waiting, "实际 socket 必须进入不可写状态");
            await(() -> AsyncEventStream.metrics().closed().getOrDefault("send_timeout", 0L) > before);
            await(() -> sessions.subscriberCount() == 0);
            assertEquals(0, AsyncEventStream.metrics().queuedBytes());
        } finally {
            sessions.delete(id);
        }
    }

    @Test
    void reconnectRestoresCurrentOutputAfterHistoryOverHttp() throws Exception {
        String id = sessions.createSession("正在生成").id();
        var run = sessions.sendMessage(id, "开始").runId();
        sessions.preview(id, run, new AssistantPreview("当前中文🙂"));
        try (Socket socket = open("/api/sessions/" + id + "/events?after=0&revision=0&protocol=2")) {
            String text = readUntil(socket, "当前中文🙂");
            assertTrue(text.indexOf("event:run.started") < text.indexOf("event:stream.preview"), text);
            assertTrue(text.contains("\"baseSequence\":1"));
            assertTrue(text.contains("\"complete\":true"));
            assertFalse(text.substring(text.indexOf("event:stream.preview")).contains("id:1"));
        } finally {
            sessions.delete(id);
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(6).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean());
    }
}
