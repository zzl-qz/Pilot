package com.pilot.core.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

class BoundedHttpTest {
    @Test
    void continuousSmallChunksCannotExtendTotalDeadline() throws Exception {
        try (Endpoint server = new Endpoint(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                for (int i = 0; i < 100; i++) {
                    out.write('a');
                    out.flush();
                    sleep(20);
                }
            }
        })) {
            long start = System.nanoTime();
            var failure = assertThrows(HttpTimeoutException.class,
                () -> server.text(Duration.ofMillis(350), Duration.ofMillis(250), 1024));
            assertTrue(failure.getMessage().contains("总超时"));
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1500);
        }
    }

    @Test
    void stalledBodyHitsIdleDeadlineBeforeTotalDeadline() throws Exception {
        try (Endpoint server = new Endpoint(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('a');
            exchange.getResponseBody().flush();
            sleep(1500);
            exchange.close();
        })) {
            long start = System.nanoTime();
            var failure = assertThrows(HttpTimeoutException.class,
                () -> server.text(Duration.ofSeconds(4), Duration.ofMillis(200), 1024));
            assertTrue(failure.getMessage().contains("空闲"));
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1200);
        }
    }

    @Test
    void delayedHeadersAlsoCountTowardDeadline() throws Exception {
        try (Endpoint server = new Endpoint(exchange -> {
            sleep(1500);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        })) {
            assertThrows(HttpTimeoutException.class,
                () -> server.text(Duration.ofMillis(200), Duration.ofSeconds(2), 1024));
        }
    }

    @RepeatedTest(5)
    void idleTimeoutBeforeHeadersKeepsItsClassificationWhenTheRequestIsCancelled() throws Exception {
        try (Endpoint server = new Endpoint(exchange -> {
            sleep(1500);
            exchange.close();
        })) {
            var failure = assertThrows(HttpTimeoutException.class,
                () -> server.text(Duration.ofSeconds(3), Duration.ofMillis(50), 1024));
            assertTrue(failure.getMessage().contains("空闲超时"));
        }
    }

    @Test
    void limitsSuccessBodyBeforeReadingUnboundedContent() throws Exception {
        try (Endpoint server = new Endpoint(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write(new byte[4096]);
            }
        })) {
            assertThrows(BoundedHttp.ResponseLimitException.class,
                () -> server.text(Duration.ofSeconds(2), Duration.ofSeconds(2), 100));
        }
    }

    @Test
    void readsOnlyErrorPrefixAndPreservesStatusWithoutWaitingForEof() throws Exception {
        try (Endpoint server = new Endpoint(exchange -> {
            exchange.sendResponseHeaders(401, 0);
            exchange.getResponseBody().write(new byte[BoundedHttp.ERROR_BYTES + 1]);
            exchange.getResponseBody().flush();
            sleep(1500);
            exchange.close();
        })) {
            var result = server.text(Duration.ofSeconds(1), Duration.ofSeconds(1), 100);
            assertEquals(401, result.statusCode());
            assertEquals(BoundedHttp.ERROR_BYTES, result.body().length());
        }
    }

    @Test
    void cancelBeforeHeadersTerminatesWaitingRequest() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try (Endpoint server = new Endpoint(exchange -> {
            started.countDown();
            sleep(2000);
            exchange.close();
        })) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            var worker = Thread.ofVirtual().start(() -> {
                try {
                    server.text(Duration.ofSeconds(20), Duration.ofSeconds(20), 100);
                } catch (Throwable e) {
                    failure.set(e);
                }
            });
            try {
                assertTrue(started.await(2, TimeUnit.SECONDS));
                worker.interrupt();
                worker.join(1500);
                assertFalse(worker.isAlive());
                assertInstanceOf(InterruptedException.class, failure.get());
            } finally {
                worker.interrupt();
            }
        }
    }

    @Test
    void boundedLinesHandleCrLfEmptyAndMultibyteText() throws Exception {
        try (var reader = new BufferedReader(new StringReader("中\r\n\r字\n末"))) {
            assertEquals("中", BoundedHttp.readLine(reader, 1));
            assertEquals("", BoundedHttp.readLine(reader, 1));
            assertEquals("字", BoundedHttp.readLine(reader, 1));
            assertEquals("末", BoundedHttp.readLine(reader, 1));
            assertNull(BoundedHttp.readLine(reader, 1));
        }
        try (var reader = new BufferedReader(new StringReader("abcd"))) {
            assertThrows(BoundedHttp.ResponseLimitException.class, () -> BoundedHttp.readLine(reader, 3));
        }
    }

    @Test
    void successfulResponsesCancelTheirTimers() throws Exception {
        try (Endpoint server = new Endpoint(exchange -> {
            exchange.sendResponseHeaders(200, 2);
            try (var out = exchange.getResponseBody()) {
                out.write("ok".getBytes(StandardCharsets.UTF_8));
            }
        })) {
            for (int i = 0; i < 20; i++) {
                assertEquals("ok", server.text(Duration.ofSeconds(1), Duration.ofSeconds(1), 2).body());
            }
            // 完成请求的定时器不能污染同一个客户端的后续请求。
            Thread.sleep(1100);
            assertEquals("ok", server.text(Duration.ofSeconds(1), Duration.ofSeconds(1), 2).body());
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Endpoint implements AutoCloseable {
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final HttpClient client = HttpClient.newHttpClient();

        Endpoint(HttpHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", handler);
            server.start();
        }

        BoundedHttp.TextResponse text(Duration total, Duration idle, int bytes) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .timeout(total).build();
            return BoundedHttp.text(client, request, idle, bytes);
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
            client.close();
        }
    }
}
