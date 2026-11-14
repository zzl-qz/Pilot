package com.pilot.core.mcp;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.pilot.core.util.HostOs;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 最小的 MCP stdio 客户端：JSON-RPC 2.0，一行一条消息。
 * 只实现 Pilot 用得到的部分：initialize、tools/list（含分页）、tools/call，并对服务器的 ping 应答。
 */
final class McpClient implements AutoCloseable {
    static final String PROTOCOL_VERSION = "2025-06-18";
    private static final int MAX_PAGES = 20;
    /** 单次等待的硬上限，防止配置里填了离谱的超时导致纳秒计算溢出。 */
    private static final Duration MAX_WAIT = Duration.ofHours(1);
    /** 回应服务端主动发来的请求时，写回执最多等这么久。 */
    private static final Duration REPLY_WRITE_TIMEOUT = Duration.ofSeconds(20);

    private final String name;
    private final McpServerConfig config;
    private final Path workdir;
    private final Path logFile;
    private final ObjectMapper mapper;
    private final String extraPath;
    private final AtomicLong ids = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Semaphore slots = new Semaphore(64);
    private final Map<CompletableFuture<Void>, Boolean> writes = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ThreadPoolExecutor writer =
        new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64), task -> {
            Thread thread = new Thread(task, "pilot-mcp-writer");
            thread.setDaemon(true);
            return thread;
        });

    private volatile Process process;
    private volatile OutputStream stdin;
    private volatile List<McpToolInfo> tools;
    private final AtomicLong toolsRevision = new AtomicLong();

    McpClient(String name, McpServerConfig config, Path workdir, Path logFile, ObjectMapper mapper, String extraPath) {
        this.name = name;
        this.config = config;
        this.workdir = canonicalWorkspace(workdir);
        this.logFile = logFile;
        this.mapper = mapper;
        this.extraPath = extraPath == null ? "" : extraPath;
    }

    boolean running() {
        Process current = process;
        return !closed.get() && current != null && current.isAlive();
    }

    static Path canonicalWorkspace(Path workspace) {
        try {
            return workspace.toRealPath();
        } catch (IOException e) {
            return workspace.toAbsolutePath().normalize();
        }
    }

    boolean matchesWorkspace(Path workspace) {
        return workdir.equals(workspace);
    }

    boolean matches(McpServerConfig current, String path) {
        return config.equals(current) && extraPath.equals(path == null ? "" : path);
    }

    long catalogRevision() {
        return toolsRevision.get();
    }

    List<McpToolInfo> cachedTools() {
        List<McpToolInfo> current = tools;
        return current == null ? List.of() : current;
    }

    void start(Duration timeout) {
        if (closed.get() || process != null) {
            throw new McpException("MCP 客户端不能重复启动");
        }
        long deadline = deadline(timeout);
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
            request("initialize", params, remainingDuration(deadline));
            awaitWrite(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"), deadline);
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
        long revision = toolsRevision.get();
        List<McpToolInfo> all = new ArrayList<>();
        long deadline = deadline(timeout);
        long bytes = 0;
        var cursors = new HashSet<String>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode result = request("tools/list", params, remainingDuration(deadline));
            bytes += mapper.writeValueAsBytes(result).length;
            if (bytes > McpJson.MAX_FRAME_BYTES) {
                throw new McpException("MCP 工具发现累计数据超过大小上限，未缓存不完整列表");
            }
            for (JsonNode item : result.path("tools")) {
                if (all.size() >= 1000) {
                    throw new McpException("MCP 工具数量超过上限（1000），未缓存不完整列表");
                }
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
                List<McpToolInfo> discovered = List.copyOf(all);
                synchronized (toolsRevision) {
                    if (toolsRevision.get() != revision) {
                        throw new McpException("MCP 工具列表在发现期间已变化，请重新查询");
                    }
                    tools = discovered;
                }
                return discovered;
            }
            if (!cursors.add(cursor)) {
                throw new McpException("MCP 工具分页游标重复，未缓存不完整列表");
            }
        }
        throw new McpException("MCP 工具分页超过上限，未缓存不完整列表");
    }

    JsonNode call(String tool, Map<String, Object> arguments, Duration timeout) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", tool);
        params.put("arguments", arguments == null ? Map.of() : arguments);
        return request("tools/call", params, timeout);
    }

    private JsonNode request(String method, Map<String, Object> params, Duration timeout) {
        long deadline = deadline(timeout);
        if (!slots.tryAcquire()) {
            throw new McpException("MCP 并发请求已达上限，尚未发送本次请求");
        }
        long id = ids.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        AtomicBoolean dispatched = new AtomicBoolean();
        pending.put(id, future);
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("jsonrpc", "2.0");
            message.put("id", id);
            message.put("method", method);
            message.put("params", params);
            write(message, deadline, dispatched).get(remaining(deadline), TimeUnit.NANOSECONDS);
            JsonNode reply = future.get(remaining(deadline), TimeUnit.NANOSECONDS);
            JsonNode error = reply.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                throw new McpException(error.path("message").asString("MCP 服务器返回错误")
                    + (error.has("code") ? "（code " + error.path("code").asInt() + "）" : ""));
            }
            return reply.path("result");
        } catch (TimeoutException e) {
            McpException failure = new McpException("MCP 服务器 " + name + " 调用超时（包含发送与等待响应：" + method + "）");
            terminate(failure);
            throw outcome(failure, method, dispatched.get());
        } catch (InterruptedException e) {
            McpException failure = new McpException("MCP 调用被中断");
            terminate(failure);
            Thread.currentThread().interrupt();
            throw outcome(failure, method, dispatched.get());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            McpException failure = cause instanceof McpException me ? me
                : new McpException(cause instanceof TimeoutException ? "MCP 发送超时" : String.valueOf(cause));
            terminate(failure);
            throw outcome(failure, method, dispatched.get());
        } finally {
            pending.remove(id);
            slots.release();
        }
    }

    private static McpException outcome(McpException failure, String method, boolean dispatched) {
        return dispatched && "tools/call".equals(method)
            ? new McpException("结果未确认：" + failure.getMessage() + "。外部操作可能已经执行；停止等待或关闭连接不代表操作已撤销，请先核对外部结果，不要自动重试。", true)
            : failure;
    }

    private void awaitWrite(Map<String, Object> message, long deadline) {
        try {
            write(message, deadline, new AtomicBoolean()).get(remaining(deadline), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("MCP 发送被中断");
        } catch (ExecutionException | TimeoutException e) {
            throw new McpException("MCP 发送失败或超时：" + e.getMessage());
        }
    }

    /** 序列化、排队、管道写入及 flush 均受同一绝对时限约束；读取线程不等待写入。 */
    private CompletableFuture<Void> write(Map<String, Object> message, long deadline, AtomicBoolean dispatched) {
        CompletableFuture<Void> written = new CompletableFuture<>();
        writes.put(written, Boolean.TRUE);
        written.orTimeout(remaining(deadline), TimeUnit.NANOSECONDS).whenComplete((ignored, error) -> {
            writes.remove(written);
            if (error instanceof TimeoutException) {
                terminate(new McpException("MCP 发送超时，连接已关闭"));
            }
        });
        try {
            if (!running()) {
                throw new McpException("MCP 服务器 " + name + " 未运行");
            }
            writer.execute(() -> {
                if (written.isDone() || closed.get()) {
                    return;
                }
                try {
                    byte[] line = (mapper.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8);
                    if (line.length > McpJson.MAX_FRAME_BYTES) {
                        throw new McpException("MCP 请求超过大小上限，尚未发送");
                    }
                    // 先标为可能发送，再检查关闭；否则关闭方可能误判未发送，而写线程随后继续写入。
                    dispatched.set(true);
                    if (written.isDone() || closed.get() || System.nanoTime() >= deadline) {
                        return;
                    }
                    stdin.write(line);
                    stdin.flush();
                    written.complete(null);
                } catch (IOException e) {
                    McpException failure = new McpException("与 MCP 服务器 " + name + " 通信失败：" + e.getMessage());
                    written.completeExceptionally(failure);
                    terminate(failure);
                } catch (RuntimeException e) {
                    written.completeExceptionally(new McpException("MCP 请求无法发送：" + e.getMessage()));
                }
            });
        } catch (RejectedExecutionException | McpException e) {
            written.completeExceptionally(new McpException("MCP 写入队列不可用：" + e.getMessage()));
        }
        return written;
    }

    private static long deadline(Duration timeout) {
        return System.nanoTime() + Math.max(1, Math.min(MAX_WAIT.toNanos(), timeout.toNanos()));
    }

    private static long remaining(long deadline) {
        return Math.max(1, deadline - System.nanoTime());
    }

    private static Duration remainingDuration(long deadline) {
        return Duration.ofNanos(remaining(deadline));
    }

    private void read(Process owner) {
        try (InputStream reader = new BufferedInputStream(owner.getInputStream())) {
            byte[] line;
            while ((line = McpJson.readFrame(reader, McpJson.MAX_FRAME_BYTES)) != null) {
                JsonNode node = McpJson.parse(line);
                if (node == null || node.isMissingNode()) {
                    continue;
                }
                boolean hasMethod = node.has("method");
                if ("notifications/tools/list_changed".equals(node.path("method").asString("")) && !node.has("id")) {
                    synchronized (toolsRevision) {
                        toolsRevision.incrementAndGet();
                        tools = null;
                    }
                } else if (hasMethod && node.has("id")) {
                    answerServerRequest(node);
                } else if (!hasMethod && node.has("id")) {
                    CompletableFuture<JsonNode> future = pending.get(node.path("id").asLong(-1));
                    if (future != null) {
                        if (!"2.0".equals(node.path("jsonrpc").asString("")) || node.has("result") == node.has("error")
                            || node.has("result") && !node.path("result").isObject()
                            || node.has("error") && !node.path("error").isObject()) {
                            throw new McpException("MCP 响应结构无效，不能确认调用结果");
                        }
                        future.complete(node);
                    }
                }
            }
        } catch (IOException | McpException e) {
            terminate(e instanceof McpException me ? me : new McpException("MCP 读取失败：" + e.getMessage()));
        } finally {
            terminate(new McpException("MCP 服务器 " + name + " 已退出或关闭输出"));
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
        write(reply, deadline(REPLY_WRITE_TIMEOUT), new AtomicBoolean());
    }

    @Override
    public void close() {
        terminate(new McpException("MCP 服务器 " + name + " 已关闭"));
    }

    private void terminate(McpException failure) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Process current = process;
        process = null;
        tools = null;
        pending.values().forEach(future -> future.completeExceptionally(failure));
        writes.keySet().forEach(future -> future.completeExceptionally(failure));
        writer.shutdownNow();
        if (current != null) {
            // Process.destroy/OutputStream.close 可能等待正在阻塞写入的流锁，先通过进程句柄终止。
            current.descendants().forEach(ProcessHandle::destroyForcibly);
            current.toHandle().destroyForcibly();
            Thread cleanup = new Thread(() -> {
                try {
                    current.getOutputStream().close();
                    current.getInputStream().close();
                    current.getErrorStream().close();
                } catch (IOException ignored) {
                    // 已终止的管道。
                }
            }, "pilot-mcp-cleanup-" + name);
            cleanup.setDaemon(true);
            cleanup.start();
        }
    }

    /** Windows 上 npx、npm 等是 .cmd 脚本，ProcessBuilder 不会自动补扩展名。 */
    private static String resolveCommand(String command) {
        if (!HostOs.isWindows()) {
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
