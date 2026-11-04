package com.pilot.core.mcp;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 最小的 MCP stdio 客户端：JSON-RPC 2.0，一行一条消息。
 * 只实现 Pilot 用得到的部分：initialize、tools/list（含分页）、tools/call，并对服务器的 ping 应答。
 */
final class McpClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(McpClient.class);
    static final String PROTOCOL_VERSION = "2025-06-18";
    private static final int MAX_PAGES = 20;

    private final String name;
    private final McpServerConfig config;
    private final Path workdir;
    private final Path logFile;
    private final ObjectMapper mapper;
    private final String extraPath;
    private final AtomicLong ids = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    private volatile Process process;
    private volatile OutputStream stdin;
    private volatile List<McpToolInfo> tools;

    McpClient(String name, McpServerConfig config, Path workdir, Path logFile, ObjectMapper mapper, String extraPath) {
        this.name = name;
        this.config = config;
        this.workdir = workdir;
        this.logFile = logFile;
        this.mapper = mapper;
        this.extraPath = extraPath == null ? "" : extraPath;
    }

    boolean running() {
        Process current = process;
        return current != null && current.isAlive();
    }

    void start(Duration timeout) {
        List<String> command = new ArrayList<>();
        command.add(resolveCommand(config.command()));
        command.addAll(config.args());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workdir.toFile());
        if (!extraPath.isBlank()) {
            String path = builder.environment().getOrDefault("PATH", "");
            builder.environment().put("PATH", extraPath + File.pathSeparator + path);
        }
        builder.environment().putAll(config.env());
        builder.redirectError(
            logFile == null ? ProcessBuilder.Redirect.DISCARD : ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        try {
            if (logFile != null && logFile.getParent() != null) {
                java.nio.file.Files.createDirectories(logFile.getParent());
            }
            Process started = builder.start();
            process = started;
            stdin = started.getOutputStream();
            Thread reader = new Thread(() -> read(started), "pilot-mcp-" + name);
            reader.setDaemon(true);
            reader.start();
        } catch (IOException e) {
            throw new McpException("无法启动 MCP 服务器 " + name + "（" + config.command() + "）：" + e.getMessage());
        }
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("protocolVersion", PROTOCOL_VERSION);
            params.put("capabilities", Map.of());
            params.put("clientInfo", Map.of("name", "pilot", "version", "0.1"));
            request("initialize", params, timeout);
            notify("notifications/initialized");
        } catch (McpException e) {
            close();
            throw e;
        }
    }

    /** 服务器声明的全部工具；首次调用时拉取并缓存。 */
    List<McpToolInfo> tools(Duration timeout) {
        List<McpToolInfo> cached = tools;
        if (cached != null) {
            return cached;
        }
        List<McpToolInfo> all = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode result = request("tools/list", params, timeout);
            for (JsonNode item : result.path("tools")) {
                String toolName = item.path("name").asString("");
                if (toolName.isBlank()) {
                    continue;
                }
                boolean readOnly = item.path("annotations").path("readOnlyHint").asBoolean(false);
                all.add(new McpToolInfo(toolName, item.path("description").asString(""), item.path("inputSchema"),
                    readOnly));
            }
            cursor = result.path("nextCursor").asString("");
            if (cursor.isBlank()) {
                break;
            }
        }
        tools = List.copyOf(all);
        return tools;
    }

    JsonNode call(String tool, Map<String, Object> arguments, Duration timeout) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", tool);
        params.put("arguments", arguments == null ? Map.of() : arguments);
        return request("tools/call", params, timeout);
    }

    private JsonNode request(String method, Map<String, Object> params, Duration timeout) {
        long id = ids.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("jsonrpc", "2.0");
            message.put("id", id);
            message.put("method", method);
            message.put("params", params);
            write(message);
            JsonNode reply = future.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            JsonNode error = reply.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                throw new McpException(error.path("message").asString("MCP 服务器返回错误")
                    + (error.has("code") ? "（code " + error.path("code").asInt() + "）" : ""));
            }
            return reply.path("result");
        } catch (TimeoutException e) {
            throw new McpException("MCP 服务器 " + name + " 在 " + timeout.toSeconds() + " 秒内没有响应（" + method + "）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("MCP 调用被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof McpException me ? me : new McpException(String.valueOf(cause));
        } finally {
            pending.remove(id);
        }
    }

    private void notify(String method) {
        write(Map.of("jsonrpc", "2.0", "method", method));
    }

    private void write(Map<String, Object> message) {
        OutputStream out = stdin;
        if (out == null) {
            throw new McpException("MCP 服务器 " + name + " 未运行");
        }
        try {
            String line = mapper.writeValueAsString(message);
            synchronized (writeLock) {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException e) {
            throw new McpException("与 MCP 服务器 " + name + " 通信失败：" + e.getMessage());
        }
    }

    private void read(Process owner) {
        try (BufferedReader reader =
            new BufferedReader(new InputStreamReader(owner.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node;
                try {
                    node = mapper.readTree(line);
                } catch (RuntimeException e) {
                    log.debug("MCP {} 输出了非 JSON 行：{}", name, line);
                    continue;
                }
                boolean hasMethod = node.has("method");
                if (hasMethod && node.has("id")) {
                    answerServerRequest(node);
                } else if (!hasMethod && node.has("id")) {
                    CompletableFuture<JsonNode> future = pending.get(node.path("id").asLong(-1));
                    if (future != null) {
                        future.complete(node);
                    }
                }
            }
        } catch (IOException ignored) {
            // 进程退出
        } finally {
            McpException gone = new McpException("MCP 服务器 " + name + " 已退出");
            pending.values().forEach(future -> future.completeExceptionally(gone));
            if (process == owner) {
                process = null;
            }
        }
    }

    private void answerServerRequest(JsonNode request) {
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("jsonrpc", "2.0");
        reply.put("id", mapper.convertValue(request.path("id"), Object.class));
        if ("ping".equals(request.path("method").asString(""))) {
            reply.put("result", Map.of());
        } else {
            reply.put("error", Map.of("code", -32601, "message", "Pilot 不支持该方法"));
        }
        try {
            write(reply);
        } catch (McpException ignored) {
            // 服务器已经走了
        }
    }

    @Override
    public void close() {
        Process current = process;
        process = null;
        tools = null;
        if (current == null) {
            return;
        }
        McpException gone = new McpException("MCP 服务器 " + name + " 已关闭");
        pending.values().forEach(future -> future.completeExceptionally(gone));
        try {
            OutputStream out = stdin;
            if (out != null) {
                out.close();
            }
        } catch (IOException ignored) {
            // 已关闭
        }
        try {
            if (!current.waitFor(2, TimeUnit.SECONDS)) {
                current.descendants().forEach(ProcessHandle::destroyForcibly);
                current.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        }
    }

    /** Windows 上 npx、npm 等是 .cmd 脚本，ProcessBuilder 不会自动补扩展名。 */
    private static String resolveCommand(String command) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return command;
        }
        String lower = command.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".exe") || lower.endsWith(".cmd") || lower.endsWith(".bat") || command.contains("/")
            || command.contains("\\")) {
            return command;
        }
        return List.of("npx", "npm", "pnpm", "yarn", "uvx").contains(lower) ? command + ".cmd" : command;
    }
}
