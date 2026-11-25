package com.pilot.core.mcp;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLSession;

import com.pilot.core.http.BoundedHttp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 远程 MCP 传输层：支持现代 Streamable HTTP（JSON + SSE 响应 + Mcp-Session-Id），
 * 以及传统 HTTP+SSE（GET 发现 endpoint + POST 发请求 + SSE 收回包）。
 *
 * <p>安全边界：不跟随重定向（防跨站或降级泄露 Authorization 头）、强制响应体积上限、
 * 异常消息永不输出请求头明文。
 */
final class McpHttpTransport implements AutoCloseable {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Set<String> RESTRICTED_HEADERS =
        Set.of("host", "content-length", "transfer-encoding", "connection", "upgrade", "http2-settings", "te");

    private final String name;
    private final McpServerConfig config;
    private final URI baseUri;
    private final ObjectMapper mapper;
    private final Runnable onToolsListChanged;
    private final HttpClient http;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong requestIds = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> ssePending = new ConcurrentHashMap<>();

    private volatile boolean connected;
    private volatile boolean legacySseMode;
    private volatile String sessionId;
    private volatile URI postEndpoint;
    private volatile InputStream activeSseStream;
    private volatile Thread sseThread;

    McpHttpTransport(String name, McpServerConfig config, ObjectMapper mapper, Runnable onToolsListChanged) {
        String invalid = McpServerConfig.validateRemoteUrl(config.url());
        if (invalid != null) {
            throw new McpException(invalid);
        }
        this.name = name;
        this.config = config;
        this.baseUri = URI.create(config.url());
        this.mapper = mapper;
        this.onToolsListChanged = onToolsListChanged == null ? () -> {
        } : onToolsListChanged;
        this.http =
            HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
        this.legacySseMode = McpServerConfig.TRANSPORT_SSE.equals(config.transport());
        this.postEndpoint = baseUri;
    }

    boolean running() {
        if (closed.get() || !connected) {
            return false;
        }
        if (legacySseMode) {
            Thread thread = sseThread;
            return thread != null && thread.isAlive();
        }
        return true;
    }

    void start(long initializeId, Map<String, Object> initParams, long deadline) {
        if (closed.get() || connected) {
            throw new McpException("MCP 客户端不能重复启动");
        }
        if (legacySseMode) {
            startLegacySse(deadline);
            request(initializeId, "initialize", initParams, deadline, new AtomicBoolean());
            sendNotification("notifications/initialized", deadline);
            connected = true;
            return;
        }
        try {
            postStreamable(initializeId, "initialize", initParams, deadline, new AtomicBoolean(), true);
            sendNotification("notifications/initialized", deadline);
            connected = true;
        } catch (ExpiredSessionException expired) {
            initializeExpiredSession(requestIds.incrementAndGet(), initParams, deadline, expired.sessionId);
            connected = true;
        } catch (FallbackToLegacySseException fallback) {
            legacySseMode = true;
            startLegacySse(deadline);
            request(initializeId, "initialize", initParams, deadline, new AtomicBoolean());
            sendNotification("notifications/initialized", deadline);
            connected = true;
        } catch (McpException e) {
            close();
            throw e;
        }
    }

    JsonNode request(long id, String method, Map<String, Object> params, long deadline, AtomicBoolean dispatched) {
        if (closed.get()) {
            throw new McpException("MCP 服务器 " + name + " 已关闭");
        }
        if (legacySseMode) {
            return requestLegacySse(id, method, params, deadline, dispatched);
        }
        try {
            return postStreamable(id, method, params, deadline, dispatched, false);
        } catch (ExpiredSessionException expired) {
            initializeExpiredSession(requestIds.incrementAndGet(), initializeParams(), deadline, expired.sessionId);
            return postStreamable(id, method, params, deadline, dispatched, false, false);
        }
    }

    private synchronized void initializeExpiredSession(long id, Map<String, Object> params, long deadline,
        String failedSessionId) {
        String current = sessionId;
        if (current != null && !current.isBlank() && !current.equals(failedSessionId)) {
            return;
        }
        sessionId = null;
        postStreamable(id, "initialize", params, deadline, new AtomicBoolean(), false, false);
        sendNotification("notifications/initialized", deadline);
    }

    private static Map<String, Object> initializeParams() {
        return Map.of("protocolVersion", McpClient.PROTOCOL_VERSION, "capabilities", Map.of(), "clientInfo",
            Map.of("name", "pilot", "version", "1"));
    }

    private JsonNode postStreamable(long id, String method, Map<String, Object> params, long deadline,
        AtomicBoolean dispatched, boolean allowSseFallback) {
        return postStreamable(id, method, params, deadline, dispatched, allowSseFallback, true);
    }

    private JsonNode postStreamable(long id, String method, Map<String, Object> params, long deadline,
        AtomicBoolean dispatched, boolean allowSseFallback, boolean retryExpiredSession) {
        byte[] payload = encodeRequest(id, method, params);
        HttpRequest.Builder req = baseRequest(baseUri, remainingDuration(deadline))
            .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", McpClient.PROTOCOL_VERSION)
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        String sentSessionId = sessionId;
        if (sentSessionId != null && !sentSessionId.isBlank()) {
            req.header("Mcp-Session-Id", sentSessionId);
        }
        dispatched.set(true);
        HttpResponse<InputStream> response = send(req.build(), method);
        try (InputStream body = response.body()) {
            int code = response.statusCode();
            if (code == 404 && retryExpiredSession && sentSessionId != null && !sentSessionId.isBlank()) {
                throw new ExpiredSessionException(sentSessionId);
            }
            if (allowSseFallback && code == 405) {
                throw new FallbackToLegacySseException();
            }
            response.headers().firstValue("Mcp-Session-Id").filter(v -> !v.isBlank())
                .ifPresent(v -> sessionId = v.strip());
            if (code >= 300 && code < 400) {
                String location = response.headers().firstValue("Location").orElse("");
                throw new McpException("远程 MCP 服务器返回重定向（HTTP " + code + (location.isBlank() ? "" : " -> " + location)
                    + "），为避免泄露认证头已停止跟随，请填写最终地址");
            }
            if (code < 200 || code >= 300) {
                String snippet = readErrorSnippet(body);
                throw new McpException(
                    "远程 MCP 服务器 " + name + " 返回 HTTP " + code + (snippet.isBlank() ? "" : "：" + snippet));
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            if (contentType.contains("text/event-stream")) {
                return readSseResponse(body, id, deadline);
            }
            byte[] bytes = readBounded(body, McpJson.MAX_FRAME_BYTES);
            return extractRpcResult(bytes, id);
        } catch (IOException e) {
            throw new McpException("读取远程 MCP 响应失败：" + e.getMessage());
        }
    }

    private void sendNotification(String method, long deadline) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        byte[] payload = encodeRaw(message);
        URI target = legacySseMode ? postEndpoint : baseUri;
        HttpRequest.Builder req = baseRequest(target, remainingDuration(deadline))
            .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", McpClient.PROTOCOL_VERSION)
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        if (sessionId != null && !sessionId.isBlank()) {
            req.header("Mcp-Session-Id", sessionId);
        }
        HttpResponse<InputStream> response = send(req.build(), method);
        try (InputStream body = response.body()) {
            int code = response.statusCode();
            if (code < 200 || code >= 300) {
                String snippet = readErrorSnippet(body);
                throw new McpException(
                    "远程 MCP 通知 " + method + " 失败（HTTP " + code + (snippet.isBlank() ? "" : "：" + snippet) + "）");
            }
        } catch (IOException ignored) {
            // 通知本身无需响应体
        }
    }

    private void startLegacySse(long deadline) {
        CompletableFuture<URI> endpointFuture = new CompletableFuture<>();
        Thread thread = new Thread(() -> runLegacySseLoop(endpointFuture), "pilot-mcp-sse-" + name);
        thread.setDaemon(true);
        sseThread = thread;
        thread.start();
        try {
            URI discovered = endpointFuture.get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
            postEndpoint = discovered;
        } catch (TimeoutException e) {
            close();
            throw new McpException("连接远程 MCP SSE 服务器 " + name + " 超时（未收到 endpoint 事件）");
        } catch (InterruptedException e) {
            close();
            Thread.currentThread().interrupt();
            throw new McpException("连接远程 MCP SSE 服务器被中断");
        } catch (ExecutionException e) {
            close();
            Throwable cause = e.getCause();
            throw cause instanceof McpException me ? me
                : new McpException("连接远程 MCP SSE 服务器失败：" + (cause == null ? e.getMessage() : cause.getMessage()));
        }
    }

    private void runLegacySseLoop(CompletableFuture<URI> endpointFuture) {
        HttpRequest req = baseRequest(baseUri, Duration.ofDays(365)).header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache").GET().build();
        try {
            // 长连接 SSE 空闲期间不能因 30s 空闲超时断开，也无整条连接累计字节上限；
            // 单行和单事件大小由 BoundedHttp.readLine 与 McpJson.MAX_FRAME_BYTES 逐条限幅。
            HttpResponse<InputStream> response = boundedSend(req, Duration.ofDays(365), Integer.MAX_VALUE);
            int code = response.statusCode();
            if (code < 200 || code >= 300) {
                try (InputStream err = response.body()) {
                    String snippet = readErrorSnippet(err);
                    endpointFuture.completeExceptionally(new McpException(
                        "远程 MCP SSE 握手失败（HTTP " + code + (snippet.isBlank() ? "" : "：" + snippet) + "）"));
                }
                return;
            }
            try (InputStream stream = response.body();
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                activeSseStream = stream;
                String eventName = "";
                StringBuilder data = new StringBuilder();
                String line;
                while (!closed.get() && (line = BoundedHttp.readLine(reader, BoundedHttp.SSE_LINE_CHARS)) != null) {
                    if (line.isEmpty()) {
                        dispatchSseEvent(eventName, data.toString(), endpointFuture);
                        eventName = "";
                        data.setLength(0);
                        continue;
                    }
                    if (line.startsWith(":")) {
                        continue;
                    }
                    if (line.startsWith("event:")) {
                        eventName = line.substring("event:".length()).strip();
                    } else if (line.startsWith("data:")) {
                        if (data.length() > 0) {
                            data.append('\n');
                        }
                        String piece = line.substring("data:".length());
                        if (piece.startsWith(" ")) {
                            piece = piece.substring(1);
                        }
                        if (data.length() + piece.length() > McpJson.MAX_FRAME_BYTES) {
                            throw new McpException("远程 MCP SSE 单条事件超过大小上限");
                        }
                        data.append(piece);
                    }
                }
                if (data.length() > 0) {
                    dispatchSseEvent(eventName, data.toString(), endpointFuture);
                }
            }
        } catch (IOException | InterruptedException | McpException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            McpException failure =
                e instanceof McpException me ? me : new McpException("远程 MCP SSE 连接断开：" + e.getMessage());
            endpointFuture.completeExceptionally(failure);
            failAllPending(failure);
        } finally {
            activeSseStream = null;
            McpException ended = new McpException("远程 MCP SSE 服务器 " + name + " 已关闭连接");
            endpointFuture.completeExceptionally(ended);
            failAllPending(ended);
        }
    }

    private void dispatchSseEvent(String eventName, String payload, CompletableFuture<URI> endpointFuture) {
        if (payload == null || payload.isBlank()) {
            return;
        }
        if ("endpoint".equals(eventName)) {
            try {
                URI resolved = resolveSseEndpoint(payload.strip());
                endpointFuture.complete(resolved);
            } catch (McpException e) {
                endpointFuture.completeExceptionally(e);
            }
            return;
        }
        JsonNode node = McpJson.parse(payload.getBytes(StandardCharsets.UTF_8));
        if (node == null || node.isMissingNode()) {
            return;
        }
        if ("notifications/tools/list_changed".equals(node.path("method").asString("")) && !node.has("id")) {
            onToolsListChanged.run();
            return;
        }
        if (!node.has("method") && node.has("id")) {
            CompletableFuture<JsonNode> future = ssePending.get(node.path("id").asLong(-1));
            if (future != null) {
                future.complete(node);
            }
        }
    }

    private URI resolveSseEndpoint(String raw) {
        URI resolved;
        try {
            resolved = baseUri.resolve(raw);
        } catch (IllegalArgumentException e) {
            throw new McpException("远程 MCP SSE 返回非法 endpoint：" + raw);
        }
        String invalid = McpServerConfig.validateRemoteUrl(resolved.toString());
        if (invalid != null) {
            throw new McpException(invalid);
        }
        if ("https".equalsIgnoreCase(baseUri.getScheme()) && !"https".equalsIgnoreCase(resolved.getScheme())) {
            throw new McpException("远程 MCP SSE 不允许从 https 降级到 http endpoint");
        }
        if (!baseUri.getHost().equalsIgnoreCase(resolved.getHost())) {
            throw new McpException("远程 MCP SSE 返回了跨主机的 endpoint，已拒绝以防认证头泄露");
        }
        return resolved;
    }

    private JsonNode requestLegacySse(long id, String method, Map<String, Object> params, long deadline,
        AtomicBoolean dispatched) {
        URI target = postEndpoint;
        if (target == null) {
            throw new McpException("远程 MCP SSE 尚未就绪");
        }
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        ssePending.put(id, future);
        try {
            byte[] payload = encodeRequest(id, method, params);
            HttpRequest req = baseRequest(target, remainingDuration(deadline))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            dispatched.set(true);
            HttpResponse<InputStream> response = send(req, method);
            try (InputStream body = response.body()) {
                int code = response.statusCode();
                if (code < 200 || code >= 300) {
                    String snippet = readErrorSnippet(body);
                    throw new McpException(
                        "远程 MCP SSE 请求失败（HTTP " + code + (snippet.isBlank() ? "" : "：" + snippet) + "）");
                }
                byte[] bytes = readBounded(body, McpJson.MAX_FRAME_BYTES);
                if (bytes.length > 0 && isJsonContentType(response)) {
                    JsonNode direct = McpJson.parse(bytes);
                    if (direct != null && direct.isObject() && direct.has("jsonrpc")) {
                        return validateAndUnwrap(direct);
                    }
                }
            }
            JsonNode reply = future.get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
            return validateAndUnwrap(reply);
        } catch (TimeoutException e) {
            throw new McpException("远程 MCP 服务器 " + name + " 调用超时（" + method + "）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("远程 MCP 调用被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof McpException me ? me : new McpException("远程 MCP 调用失败：" + cause);
        } catch (IOException e) {
            throw new McpException("读取远程 MCP SSE 响应失败：" + e.getMessage());
        } finally {
            ssePending.remove(id);
        }
    }

    private JsonNode readSseResponse(InputStream body, long requestId, long deadline) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            long totalBytes = 0;
            String line;
            while ((line = BoundedHttp.readLine(reader, BoundedHttp.SSE_LINE_CHARS)) != null) {
                if (System.nanoTime() >= deadline) {
                    throw new McpException("远程 MCP 流式响应读取超时");
                }
                totalBytes += line.length() + 1L;
                if (totalBytes > McpJson.MAX_FRAME_BYTES) {
                    throw new McpException("远程 MCP 流式响应超过大小上限");
                }
                if (line.isEmpty()) {
                    JsonNode matched = tryConsumeSseBlock(data.toString(), requestId);
                    data.setLength(0);
                    if (matched != null) {
                        return matched;
                    }
                    continue;
                }
                if (line.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    String piece = line.substring("data:".length());
                    if (piece.startsWith(" ")) {
                        piece = piece.substring(1);
                    }
                    data.append(piece);
                }
            }
            if (data.length() > 0) {
                JsonNode matched = tryConsumeSseBlock(data.toString(), requestId);
                if (matched != null) {
                    return matched;
                }
            }
        }
        throw new McpException("远程 MCP 流式响应提前结束，未收到请求 " + requestId + " 的返回结果");
    }

    private JsonNode tryConsumeSseBlock(String block, long requestId) {
        if (block == null || block.isBlank()) {
            return null;
        }
        JsonNode node = McpJson.parse(block.getBytes(StandardCharsets.UTF_8));
        if (node == null || node.isMissingNode()) {
            return null;
        }
        if ("notifications/tools/list_changed".equals(node.path("method").asString("")) && !node.has("id")) {
            onToolsListChanged.run();
            return null;
        }
        if (!node.has("method") && node.has("id") && node.path("id").asLong(-1) == requestId) {
            return validateAndUnwrap(node);
        }
        return null;
    }

    private JsonNode extractRpcResult(byte[] bytes, long requestId) {
        if (bytes.length == 0) {
            throw new McpException("远程 MCP 服务器返回空响应");
        }
        JsonNode parsed = McpJson.parse(bytes);
        if (parsed == null || parsed.isMissingNode()) {
            throw new McpException("远程 MCP 服务器返回的响应不是合法 JSON-RPC");
        }
        if (parsed.isArray()) {
            for (JsonNode item : parsed) {
                if (!item.has("method") && item.has("id") && item.path("id").asLong(-1) == requestId) {
                    return validateAndUnwrap(item);
                }
            }
            throw new McpException("远程 MCP 批量响应中未包含请求 " + requestId + " 的结果");
        }
        return validateAndUnwrap(parsed);
    }

    private JsonNode validateAndUnwrap(JsonNode node) {
        if (!"2.0".equals(node.path("jsonrpc").asString("")) || node.has("result") == node.has("error")
            || node.has("result") && !node.path("result").isObject()
            || node.has("error") && !node.path("error").isObject()) {
            throw new McpException("MCP 响应结构无效，不能确认调用结果");
        }
        JsonNode error = node.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new McpException(error.path("message").asString("MCP 服务器返回错误")
                + (error.has("code") ? "（code " + error.path("code").asInt() + "）" : ""));
        }
        return node.path("result");
    }

    private HttpResponse<InputStream> send(HttpRequest request, String method) {
        try {
            Duration idleTimeout = request.timeout().orElse(Duration.ofSeconds(30));
            return boundedSend(request, idleTimeout, McpJson.MAX_FRAME_BYTES);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new McpException("远程 MCP 服务器 " + name + " 调用超时（" + method + "）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("远程 MCP 调用被中断");
        } catch (IOException e) {
            throw new McpException("无法连接远程 MCP 服务器 " + name + "：" + e.getMessage());
        }
    }

    private HttpResponse<InputStream> boundedSend(HttpRequest request, Duration idleTimeout, int maxBytes)
        throws IOException, InterruptedException {
        BoundedHttp.Exchange exchange = BoundedHttp.open(http, request, idleTimeout, maxBytes);
        InputStream body = new FilterInputStream(exchange.body()) {
            @Override
            public void close() throws IOException {
                exchange.close();
            }
        };
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return exchange.statusCode();
            }

            @Override
            public HttpRequest request() {
                return request;
            }

            @Override
            public Optional<HttpResponse<InputStream>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public HttpHeaders headers() {
                return exchange.headers();
            }

            @Override
            public InputStream body() {
                return body;
            }

            @Override
            public Optional<SSLSession> sslSession() {
                return Optional.empty();
            }

            @Override
            public URI uri() {
                return request.uri();
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }

    private HttpRequest.Builder baseRequest(URI target, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(target);
        if (timeout != null) {
            builder.timeout(timeout);
        }
        for (Map.Entry<String, String> entry : config.headers().entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().strip();
            String value = entry.getValue();
            if (key.isEmpty() || value == null || RESTRICTED_HEADERS.contains(key.toLowerCase(Locale.ROOT))) {
                continue;
            }
            builder.header(key, value);
        }
        return builder;
    }

    private byte[] encodeRequest(long id, String method, Map<String, Object> params) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.put("params", params == null ? Map.of() : params);
        return encodeRaw(message);
    }

    private byte[] encodeRaw(Map<String, Object> message) {
        byte[] bytes = mapper.writeValueAsString(message).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > McpJson.MAX_FRAME_BYTES) {
            throw new McpException("MCP 请求超过大小上限，尚未发送");
        }
        return bytes;
    }

    private static byte[] readBounded(InputStream stream, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int total = 0;
        int n;
        while ((n = stream.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new McpException("远程 MCP 响应超过大小上限（" + maxBytes + " 字节）");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String readErrorSnippet(InputStream stream) {
        try {
            byte[] buf = stream.readNBytes(512);
            return new String(buf, StandardCharsets.UTF_8).replaceAll("\\s+", " ").strip();
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean isJsonContentType(HttpResponse<?> response) {
        String ct = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        return ct.contains("application/json") || ct.contains("+json");
    }

    private static long remainingNanos(long deadline) {
        return Math.max(1L, deadline - System.nanoTime());
    }

    private static Duration remainingDuration(long deadline) {
        return Duration.ofNanos(remainingNanos(deadline));
    }

    private void failAllPending(McpException failure) {
        ssePending.values().forEach(future -> future.completeExceptionally(failure));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        connected = false;
        String sid = sessionId;
        sessionId = null;
        InputStream sse = activeSseStream;
        activeSseStream = null;
        if (sse != null) {
            try {
                sse.close();
            } catch (IOException ignored) {
                // 忽略已关闭流
            }
        }
        failAllPending(new McpException("远程 MCP 服务器 " + name + " 已关闭"));
        if (sid != null && !sid.isBlank() && !legacySseMode) {
            try {
                HttpRequest deleteReq =
                    baseRequest(baseUri, Duration.ofSeconds(3)).header("Mcp-Session-Id", sid).DELETE().build();
                try (var ignored = BoundedHttp.open(http, deleteReq, Duration.ofSeconds(3), BoundedHttp.ERROR_BYTES)) {
                    // 仅需完成受限 DELETE 通知，响应体直接丢弃关闭
                }
            } catch (IOException | InterruptedException | RuntimeException ignored) {
                if (ignored instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (http instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // JDK 25 HttpClient supports close; this remains compatible with older JDKs.
            }
        }
    }

    private static final class FallbackToLegacySseException extends RuntimeException {}

    private static final class ExpiredSessionException extends RuntimeException {
        private final String sessionId;

        private ExpiredSessionException(String sessionId) {
            this.sessionId = sessionId;
        }
    }
}
