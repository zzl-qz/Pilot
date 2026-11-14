package com.pilot.core.runtime.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

class RuntimeAssetsTest {
    @TempDir
    Path directory;

    private byte[] zip(String name, String content) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private RuntimeAssets assets(boolean network) {
        return new RuntimeAssets(directory, new Properties(), network, value -> {
        }, HttpClient.newHttpClient(), Duration.ofSeconds(2), true);
    }

    private RuntimeAssets.Asset asset(URI uri, byte[] content) throws Exception {
        return new RuntimeAssets.Asset("fixture", uri,
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)), "managed-fixture");
    }

    @Test
    void nodeMirrorsUseTheSamePinnedArtifactAcrossSupportedPlatforms() throws Exception {
        var assets = new RuntimeAssets(directory, RuntimeAssets.manifest(directory), false, ignored -> {
        });
        for (String platform : new String[] {"windows.x64", "windows.aarch64", "mac.x64", "mac.aarch64", "linux.x64",
            "linux.aarch64"}) {
            var asset = assets.asset("node." + platform);
            assertEquals("https", asset.fallback().getScheme());
            assertEquals("npmmirror.com", asset.fallback().getHost());
            assertTrue(asset.fallback().getPath().endsWith(asset.uri().getPath().substring("/dist".length())));
        }
    }

    @Test
    void fallsBackOnDownloadFailureAndStillChecksThePinnedHash() throws Exception {
        byte[] bytes = zip("tool/bin/node", "verified archive fixture");
        var primaryRequests = new AtomicInteger();
        var fallbackRequests = new AtomicInteger();
        var primary = server("unverified bytes".getBytes(StandardCharsets.UTF_8), primaryRequests);
        var fallback = server(bytes, fallbackRequests);
        try {
            var original = asset(uri(primary), bytes);
            var withFallback = new RuntimeAssets.Asset(original.name(), original.uri(), original.sha256(),
                original.home(), uri(fallback));
            Path home = assets(true).install(withFallback);
            assertEquals(1, primaryRequests.get());
            assertEquals(1, fallbackRequests.get());
            assertTrue(RuntimeAssets.verified(home, withFallback));
            assertEquals(home, assets(false).install(withFallback));
        } finally {
            primary.stop(0);
            fallback.stop(0);
        }
    }

    @Test
    void concurrentInstallPublishesOneVerifiedCopyAndOfflineReusesIt() throws Exception {
        byte[] bytes = zip("tool/bin/中文 文件.txt", "已校验的工具");
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(bytes, requests);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var asset = asset(uri(server), bytes);
            var first = workers.submit(() -> assets(true).install(asset));
            var second = workers.submit(() -> assets(true).install(asset));
            assertEquals(first.get(), second.get());
            assertEquals(1, requests.get());
            assertEquals("已校验的工具", Files.readString(first.get().resolve("bin/中文 文件.txt")));
            assertEquals(first.get(), assets(false).install(asset));
            assertEquals(1, requests.get());
            assertEquals(asset.sha256(), Files.readString(first.get().resolve(".pilot-verified")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void badChecksumDoesNotReplaceExistingFilesAndDoesNotLeavePartialDownload() throws Exception {
        byte[] bytes = zip("tool/file", "网络内容");
        HttpServer server = server(bytes, new AtomicInteger());
        Path old = directory.resolve("managed-fixture");
        Files.createDirectories(old);
        Files.writeString(old.resolve("valuable"), "原有可用文件");
        try {
            var asset = asset(uri(server), "错误摘要".getBytes());
            assertThrows(IOException.class, () -> assets(true).install(asset));
            assertEquals("原有可用文件", Files.readString(old.resolve("valuable")));
            try (var paths = Files.list(directory)) {
                assertFalse(paths.anyMatch(p -> p.getFileName().toString().endsWith(".part")));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsZipTraversalAndTruncatedArchiveWithoutPublishing() throws Exception {
        for (byte[] bytes : new byte[][] {zip("../../outside.txt", "不应写出"), new byte[] {80, 75, 3, 4, 0}}) {
            HttpServer server = server(bytes, new AtomicInteger());
            try {
                assertThrows(IOException.class, () -> assets(true).install(asset(uri(server), bytes)));
                assertFalse(Files.exists(directory.resolve("managed-fixture/.pilot-verified")));
                assertFalse(Files.exists(directory.getParent().resolve("outside.txt")));
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void offlineMissAndPlainHttpAreRejectedBeforeNetworkRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        byte[] bytes = zip("tool/file", "fixture");
        HttpServer server = server(bytes, requests);
        try {
            var asset = asset(uri(server), bytes);
            assertThrows(IOException.class, () -> assets(false).download(asset));
            var production = new RuntimeAssets(directory, new Properties(), true, value -> {
            });
            assertThrows(IOException.class, () -> production.download(asset));
            assertEquals(0, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stalledBodyIsBoundedAndNeverMarkedVerified() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var workers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(workers);
        server.createContext("/asset.zip", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(new byte[] {1});
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        var assets = new RuntimeAssets(directory, new Properties(), true, value -> {
        }, HttpClient.newHttpClient(), Duration.ofMillis(200), true);
        try {
            assertThrows(IOException.class, () -> assets.install(asset(uri(server), new byte[] {1})));
            assertFalse(Files.exists(directory.resolve("managed-fixture/.pilot-verified")));
        } finally {
            server.stop(0);
            workers.shutdownNow();
        }
    }

    private HttpServer server(byte[] bytes, AtomicInteger requests) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/asset.zip", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private URI uri(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/asset.zip");
    }
}
