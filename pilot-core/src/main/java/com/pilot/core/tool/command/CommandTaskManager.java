package com.pilot.core.tool.command;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.pilot.core.util.HostOs;
import com.pilot.core.util.SecretScrubber;
import com.pilot.core.util.WireEnum;

import tools.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/** Owns command processes and their durable, append-only, redacted logs. */
@Slf4j
public final class CommandTaskManager implements AutoCloseable {
    /** Windows 中文控制台的默认编码，作为非 UTF-8 输出的回退。 */
    private static final Charset GBK = Charset.forName("GBK");

    public record Task(String taskId, String sessionId, String command, String workdir, String status, Long pid,
        Integer exitCode, Instant startedAt, Instant finishedAt, boolean background, long timeoutSeconds, String reason,
        CommandChangeTracker.Handle changeTracking) {
        /** Backward-compatible constructor for tests and command records created before change tracking. */
        public Task(String taskId, String sessionId, String command, String workdir, String status, Long pid,
            Integer exitCode, Instant startedAt, Instant finishedAt, boolean background, long timeoutSeconds,
            String reason) {
            this(taskId, sessionId, command, workdir, status, pid, exitCode, startedAt, finishedAt, background,
                timeoutSeconds, reason, null);
        }
    }

    /** Offsets and limits count Unicode code points; callers should reuse nextOffset unchanged. */
    public record LogChunk(String taskId, long offset, long nextOffset, String text, boolean eof, boolean truncated) {
    }

    private static final Pattern SECRET_KEY = Pattern.compile("(?i)(key|token|secret|password|credential|passwd)");
    private static final String REDACTED = "[REDACTED]";
    private static final long DEFAULT_OUTPUT_LIMIT = 10L * 1024 * 1024;
    /** 单次写入标准输入的字符上限；一次写太多会挤满管道缓冲让写线程挂住。 */
    static final int MAX_INPUT_CHARS = 8192;
    private final Path storage;
    private final ObjectMapper mapper;
    private final long outputLimit;
    private final int concurrencyLimit;
    private final int retainedLimit;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean closed;

    public CommandTaskManager(Path storage, ObjectMapper mapper) {
        this(storage, mapper, DEFAULT_OUTPUT_LIMIT, 16, 128);
    }

    CommandTaskManager(Path storage, ObjectMapper mapper, long outputLimit, int concurrencyLimit, int retainedLimit) {
        this.storage = Objects.requireNonNull(storage).toAbsolutePath().normalize();
        this.mapper = Objects.requireNonNull(mapper);
        if (outputLimit < 1 || concurrencyLimit < 1 || retainedLimit < concurrencyLimit) {
            throw new IllegalArgumentException("Invalid command task limits");
        }
        this.outputLimit = outputLimit;
        this.concurrencyLimit = concurrencyLimit;
        this.retainedLimit = retainedLimit;
        recover();
    }

    public synchronized Task start(String sessionId, String displayCommand, ProcessBuilder builder, boolean background,
        long timeoutMillis) {
        return start(sessionId, displayCommand, builder, background, timeoutMillis, null);
    }

    public synchronized Task start(String sessionId, String displayCommand, ProcessBuilder builder, boolean background,
        long timeoutMillis, CommandChangeTracker.Handle changeTracking) {
        return start(sessionId, displayCommand, builder, background, timeoutMillis, changeTracking, false);
    }

    /**
     * @param interactive 保留标准输入不关闭，之后可用 {@link #input} 逐次写入。
     *        默认关闭，让等输入的交互式命令立刻拿到 EOF 而不是一直挂到超时。
     */
    public synchronized Task start(String sessionId, String displayCommand, ProcessBuilder builder, boolean background,
        long timeoutMillis, CommandChangeTracker.Handle changeTracking, boolean interactive) {
        sessionDirectory(sessionId);
        Objects.requireNonNull(builder, "builder must not be null");
        if (closed || Files.exists(sessionDirectory(sessionId).resolve(".closed"))) {
            throw new IllegalStateException("命令任务所属会话已关闭");
        }
        if (timeoutMillis < 0 || (timeoutMillis == 0 && !background)) {
            throw new IllegalArgumentException("前台命令超时必须大于零；后台命令可设为零以持续运行");
        }
        if (displayCommand == null || displayCommand.isBlank() || displayCommand.length() > 8192) {
            throw new IllegalArgumentException("命令不能为空且不能超过 8192 字符");
        }
        if (entries.values().stream().filter(entry -> running(entry.task)).count() >= concurrencyLimit) {
            throw new IllegalStateException("后台命令并发数已达上限 " + concurrencyLimit);
        }
        prune(retainedLimit - 1);
        List<String> secrets =
            builder.environment().entrySet().stream().filter(value -> SECRET_KEY.matcher(value.getKey()).find())
                .map(Map.Entry::getValue).filter(value -> value != null && !value.isEmpty()).distinct()
                .sorted(Comparator.comparingInt(String::length).reversed()).toList();
        Path workdir = canonical(builder.directory() == null ? Path.of("") : builder.directory().toPath());
        String id = "cmd_" + UUID.randomUUID();
        Task task = new Task(id, sessionId, redact(displayCommand, secrets), workdir.toString(),
            CommandTaskStatus.RUNNING.name(), null, null, Instant.now(), null, background,
            timeoutMillis / 1000 + (timeoutMillis % 1000 == 0 ? 0 : 1), "");
        task = new Task(task.taskId(), task.sessionId(), task.command(), task.workdir(), task.status(), task.pid(),
            task.exitCode(), task.startedAt(), task.finishedAt(), task.background(), task.timeoutSeconds(),
            task.reason(), changeTracking);
        Entry entry = new Entry(task, secrets, interactive);
        try {
            // 元数据先于进程落盘：进程存在之前记录必须已在盘上，崩溃恢复才不会漏掉在跑的命令。
            persist(entry);
            builder.directory(workdir.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectInput(ProcessBuilder.Redirect.PIPE);
            entry.process = builder.start();
            // pid 必须立刻可见：状态查询、进程树停止和所有权判定都依赖它，
            // 不能等子进程发现做完，否则会读到 pid 为空的运行中任务。
            entry.task = state(entry.task, CommandTaskStatus.RUNNING, pid(entry.process), null, null, "");
            persist(entry);
            // Sample before metadata I/O and again before scheduling, reducing the window for short-lived wrappers.
            // Already detached children cannot be recovered portably; callers should run servers in foreground mode.
            entry.discover();
            // 注册放在 spawn 之后：任务一旦对外可见（list/get/stop）就必然带着 pid 与进程引用，
            // 否则「start 后立刻查 list」的调用方会拿到 pid=null 的快照（ProcessHandle.of 会 NPE）。
            // 状态先落盘再发布：观察者看到某个状态时，它的元数据一定已经在盘上。
            entries.put(id, entry);
            if (!interactive) {
                entry.process.getOutputStream().close();
            }
            entry.reader = workers.submit(() -> readOutput(entry));
            entry.discover();
            workers.submit(() -> supervise(entry, timeoutMillis));
        } catch (IOException | RuntimeException failure) {
            log.warn("命令启动失败（{}）：{}", displayCommand, failure.toString());
            if (entry.process != null) {
                entry.process.descendants().forEach(ProcessHandle::destroyForcibly);
                entry.process.destroyForcibly();
            }
            Task failed = state(entry.task, CommandTaskStatus.FAILED, pid(entry.process), null, Instant.now(),
                redact("命令启动失败：" + failure.getMessage(), secrets));
            persist(entry, failed);
            entry.task = failed;
            // 启动失败同样要可见：调用方拿到 FAILED 回执，list 也能查到，不会永远等一个不存在的任务。
            entries.putIfAbsent(id, entry);
        }
        return entry.task;
    }

    /**
     * 向以 interactive 启动、仍在运行的命令写标准输入。
     *
     * @param endInput 为 true 时写完关闭标准输入（EOF），之后不再接受输入
     * @return 写完后该任务的最新快照；当前会话没有该任务时返回 null
     * @throws IllegalStateException 命令已结束，或该任务没有打开的输入通道
     */
    public Task input(String sessionId, String taskId, String text, boolean endInput, String ownerSessionId) {
        Entry entry = owned(ownerOf(sessionId, ownerSessionId), taskId);
        if (entry == null) {
            return null;
        }
        if (text != null && text.length() > MAX_INPUT_CHARS) {
            throw new IllegalArgumentException("单次输入不能超过 " + MAX_INPUT_CHARS + " 字符");
        }
        synchronized (entry.inputLock) {
            if (!running(entry.task)) {
                throw new IllegalStateException("命令已结束（" + entry.task.status() + "），不能再写输入");
            }
            if (!entry.stdinOpen) {
                throw new IllegalStateException("该命令没有打开的输入通道：启动时未带 interactive=true，或输入已关闭");
            }
            try {
                OutputStream stdin = entry.process.getOutputStream();
                if (text != null && !text.isEmpty()) {
                    stdin.write(text.getBytes(StandardCharsets.UTF_8));
                }
                stdin.flush();
                if (endInput) {
                    // 先记关闭再 close：close 抛错时通道已经不可再用，不能留在「打开」状态上。
                    entry.stdinOpen = false;
                    stdin.close();
                }
            } catch (IOException e) {
                entry.stdinOpen = false;
                throw new IllegalStateException("写入命令输入失败：" + e.getMessage());
            }
        }
        return entry.task;
    }

    /** 距离最近一次新输出过了多久。还没有任何输出时，从进程启动算起。 */
    public long quietMillis(String sessionId, String taskId) {
        Entry entry = owned(sessionId, taskId);
        if (entry == null) {
            return 0;
        }
        long mark;
        synchronized (entry.logLock) {
            mark = entry.lastOutputNanos > 0 ? entry.lastOutputNanos : entry.startedNanos;
        }
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0, System.nanoTime() - mark));
    }

    /** 前台等到该放手时调用：进程继续跑，只是不再占着这一轮。 */
    public void markBackground(String sessionId, String taskId) {
        Entry entry = owned(sessionId, taskId);
        if (entry == null) {
            return;
        }
        synchronized (entry) {
            Task current = entry.task;
            if (current.background() || !running(current)) {
                return;
            }
            publish(entry,
                new Task(current.taskId(), current.sessionId(), current.command(), current.workdir(), current.status(),
                    current.pid(), current.exitCode(), current.startedAt(), current.finishedAt(), true,
                    current.timeoutSeconds(), current.reason(), current.changeTracking()));
        }
    }

    public Task get(String sessionId, String taskId) {
        return get(sessionId, taskId, null);
    }

    /**
     * 查询命令任务。{@code ownerSessionId} 非空时按它查找，用于在新会话里接手上一个会话留下的任务；
     * 为空或与当前会话相同则仍按当前会话隔离。
     */
    public Task get(String sessionId, String taskId, String ownerSessionId) {
        Entry entry = owned(ownerOf(sessionId, ownerSessionId), taskId);
        return entry == null ? null : entry.task;
    }

    /** 返回命令日志已落盘的码点总数；不存在时返回 0。 */
    public long logLength(String sessionId, String taskId) {
        Entry entry = owned(sessionId, taskId);
        if (entry == null) {
            return 0L;
        }
        synchronized (entry.logLock) {
            return entry.logPoints;
        }
    }

    public List<Task> list(String sessionId) {
        sessionDirectory(sessionId);
        return entries.values().stream().map(entry -> entry.task).filter(task -> task.sessionId().equals(sessionId))
            .sorted(Comparator.comparing(Task::startedAt).reversed()).toList();
    }

    /**
     * 跨会话列出本机全部命令任务，运行中的排在前面（同一组内按启动时间倒序）。
     *
     * <p>会话关闭会连带终止它自己的任务，所以这里看到的运行中任务通常来自另一个仍开着的会话；
     * 要接着查日志或停止，把它的 {@code sessionId} 作为 ownerSessionId 传回来即可。
     */
    public List<Task> listAll() {
        return entries.values().stream().map(entry -> entry.task).sorted(Comparator
            .comparing((Task task) -> !running(task)).thenComparing(Task::startedAt, Comparator.reverseOrder()))
            .toList();
    }

    public LogChunk logs(String sessionId, String taskId, long offset, int limit) {
        Entry entry = owned(ownerOf(sessionId, null), taskId);
        return logs(entry, taskId, offset, limit);
    }

    /** 跨会话读日志；ownerSessionId 非空时按它查找。 */
    public LogChunk logs(String sessionId, String taskId, long offset, int limit, String ownerSessionId) {
        return logs(owned(ownerOf(sessionId, ownerSessionId), taskId), taskId, offset, limit);
    }

    private LogChunk logs(Entry entry, String taskId, long offset, int limit) {
        if (entry == null) {
            return null;
        }
        if (offset < 0 || limit < 1) {
            throw new IllegalArgumentException("日志 offset 必须非负，limit 必须大于零");
        }
        limit = Math.min(limit, 65_536);
        synchronized (entry.logLock) {
            long actual = 0;
            StringBuilder text = new StringBuilder();
            try {
                Path log = logFile(entry.task);
                if (Files.exists(log)) {
                    try (Reader reader = Files.newBufferedReader(log, StandardCharsets.UTF_8)) {
                        while (actual < offset && readCodePoint(reader) >= 0) {
                            actual++;
                        }
                        for (int count = 0; count < limit; count++) {
                            int point = readCodePoint(reader);
                            if (point < 0) {
                                break;
                            }
                            text.appendCodePoint(point);
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("读取命令日志失败", e);
            }
            long next = actual + text.codePointCount(0, text.length());
            return new LogChunk(taskId, actual, next, text.toString(), !running(entry.task) && next >= entry.logPoints,
                CommandTaskStatus.OUTPUT_LIMIT.is(entry.task.status()));
        }
    }

    public Task stop(String sessionId, String taskId) {
        return stop(sessionId, taskId, null);
    }

    /** 跨会话停止；ownerSessionId 非空时按它查找，用于接手上一个会话留下的任务。 */
    public Task stop(String sessionId, String taskId, String ownerSessionId) {
        Entry entry = owned(ownerOf(sessionId, ownerSessionId), taskId);
        if (entry == null) {
            return null;
        }
        requestStop(entry, CommandTaskStatus.CANCELLED, "用户停止了命令");
        return entry.task;
    }

    /** Retires the owner as well as stopping its processes, preventing late agents from starting new work. */
    public synchronized void stopSession(String sessionId) {
        try {
            Path directory = sessionDirectory(sessionId);
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(".closed"), "closed", StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        entries.values().stream().filter(entry -> entry.task.sessionId().equals(sessionId))
            .forEach(entry -> requestStop(entry, CommandTaskStatus.CANCELLED, "会话已关闭"));
    }

    /** 正在占用该工作区（含父子目录）的后台命令，用于把「谁挡着」告诉用户。 */
    public List<Task> runningInWorkspace(Path workspace) {
        Path path = canonical(workspace);
        return entries.values().stream().filter(entry -> running(entry.task)).filter(
            entry -> Path.of(entry.task.workdir()).startsWith(path) || path.startsWith(Path.of(entry.task.workdir())))
            .map(entry -> entry.task).toList();
    }

    public boolean hasRunningInWorkspace(Path workspace) {
        return !runningInWorkspace(workspace).isEmpty();
    }

    public boolean hasRunningTasks() {
        return entries.values().stream().anyMatch(entry -> running(entry.task));
    }

    private void supervise(Entry entry, long timeoutMillis) {
        long started = System.nanoTime();
        try {
            while (true) {
                entry.discover();
                if (!entry.process.isAlive() && !entry.hasLiveDescendants()) {
                    break;
                }
                if (timeoutMillis > 0 && entry.stopStatus == null
                    && System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(timeoutMillis)) {
                    requestStop(entry, CommandTaskStatus.TIMED_OUT, "命令超过运行时限，已终止进程树");
                }
                if (entry.stopStatus != null) {
                    terminate(entry);
                }
                Thread.sleep(25);
            }
            // A process exit can precede the last output read; preserve the tail before publishing terminal state.
            awaitReader(entry);
            int exit = entry.process.exitValue();
            synchronized (entry) {
                CommandTaskStatus status = entry.stopStatus != null ? entry.stopStatus
                    : exit == 0 ? CommandTaskStatus.COMPLETED : CommandTaskStatus.FAILED;
                String reason = entry.stopReason != null ? entry.stopReason : exit == 0 ? "" : "命令退出码 " + exit;
                publish(entry, state(entry.task, status, entry.task.pid(), exit, Instant.now(), reason));
            }
        } catch (InterruptedException e) {
            requestStop(entry, CommandTaskStatus.INTERRUPTED, "服务停止，命令已终止");
            forceTree(entry);
            awaitReader(entry);
            synchronized (entry) {
                publish(entry, state(entry.task, entry.stopStatus, entry.task.pid(),
                    entry.process.isAlive() ? null : entry.process.exitValue(), Instant.now(), entry.stopReason));
            }
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            forceTree(entry);
            synchronized (entry) {
                publish(entry, state(entry.task, CommandTaskStatus.FAILED, entry.task.pid(), null, Instant.now(),
                    redact("命令监控失败：" + e.getMessage(), entry.secrets)));
            }
        }
    }

    /**
     * 增量读取进程输出：读到就写日志，不等换行，也不等进程结束。
     * 编码先按严格 UTF-8；一旦出现非法字节说明是本地编码（Windows 中文环境常见 GBK），
     * 之后按 GBK 解码，避免模型与用户看到一串乱码。
     */
    private void readOutput(Entry entry) {
        StreamingRedactor redactor = new StreamingRedactor(entry.secrets);
        OutputDecoder decoder = new OutputDecoder();
        long inputChars = 0;
        try (InputStream in = entry.process.getInputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) >= 0) {
                String decoded = decoder.decode(buffer, count, false);
                int accepted = (int)Math.min(decoded.length(), outputLimit - inputChars);
                inputChars += accepted;
                append(entry,
                    SecretScrubber.scrub(redactor.accept(decoded.substring(0, Math.max(0, accepted)), false)));
                if (accepted < decoded.length()) {
                    requestStop(entry, CommandTaskStatus.OUTPUT_LIMIT, "命令输出达到上限 " + outputLimit + " 字节，已终止进程树");
                    break;
                }
            }
            if (entry.stopStatus != CommandTaskStatus.OUTPUT_LIMIT) {
                append(entry, SecretScrubber.scrub(redactor.accept(decoder.decode(new byte[0], 0, true), false)));
                // A cap may cut a secret halfway; discard that withheld suffix instead of exposing a partial credential.
                append(entry, SecretScrubber.scrub(redactor.accept("", true)));
            }
        } catch (IOException e) {
            if (entry.stopStatus == null) {
                requestStop(entry, CommandTaskStatus.FAILED, "读取命令输出失败");
            }
        } catch (RuntimeException e) {
            log.warn("读取命令输出异常：{}", e.toString());
            if (entry.stopStatus == null) {
                requestStop(entry, CommandTaskStatus.FAILED, "读取命令输出异常：" + e);
            }
        }
    }

    private void append(Entry entry, String output) throws IOException {
        if (output.isEmpty()) {
            return;
        }
        synchronized (entry.logLock) {
            long available = outputLimit - entry.logBytes;
            int end = 0;
            long bytes = 0;
            while (end < output.length()) {
                int point = output.codePointAt(end);
                int size = point <= 0x7f ? 1 : point <= 0x7ff ? 2 : point <= 0xffff ? 3 : 4;
                if (bytes + size > available) {
                    break;
                }
                bytes += size;
                end += Character.charCount(point);
            }
            String kept = output.substring(0, end);
            if (!kept.isEmpty()) {
                Files.writeString(logFile(entry.task), kept, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
                entry.logBytes += bytes;
                entry.logPoints += kept.codePointCount(0, kept.length());
                entry.lastOutputNanos = System.nanoTime();
            }
            if (end < output.length()) {
                requestStop(entry, CommandTaskStatus.OUTPUT_LIMIT, "命令输出达到上限 " + outputLimit + " 字节，已终止进程树");
            }
        }
    }

    private void requestStop(Entry entry, CommandTaskStatus status, String reason) {
        synchronized (entry) {
            if (!running(entry.task) || entry.stopStatus != null) {
                return;
            }
            entry.stopReason = reason;
            entry.stoppingAt = System.nanoTime();
            entry.stopStatus = status;
            publish(entry, state(entry.task, CommandTaskStatus.STOPPING, entry.task.pid(), null, null, reason));
        }
    }

    private void terminate(Entry entry) {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - entry.stoppingAt);
        for (OwnedProcess child : entry.descendants.values()) {
            if (!child.alive()) {
                continue;
            }
            if (elapsed >= 500) {
                child.handle.destroyForcibly();
            } else {
                child.handle.destroy();
            }
        }
        if (elapsed >= 100 && entry.process.isAlive()) {
            if (elapsed >= 600) {
                entry.process.destroyForcibly();
            } else {
                entry.process.destroy();
            }
        }
    }

    private void forceTree(Entry entry) {
        entry.discover();
        entry.descendants.values().stream().filter(OwnedProcess::alive)
            .forEach(child -> child.handle.destroyForcibly());
        entry.process.destroyForcibly();
        try {
            entry.process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void awaitReader(Entry entry) {
        try {
            entry.reader.get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            try {
                entry.process.getInputStream().close();
            } catch (IOException ignored) {
            }
            try {
                entry.reader.get(500, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            entries.values().forEach(entry -> requestStop(entry, CommandTaskStatus.INTERRUPTED, "服务停止，命令已终止"));
            workers.shutdown();
        }
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                workers.shutdownNow();
                workers.awaitTermination(3, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void recover() {
        try {
            Files.createDirectories(storage);
            try (DirectoryStream<Path> sessions = Files.newDirectoryStream(storage)) {
                for (Path directory : sessions) {
                    if (!Files.isDirectory(directory) || Files.isSymbolicLink(directory)) {
                        continue;
                    }
                    try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "cmd_*.json")) {
                        for (Path file : files) {
                            try {
                                recoverTask(file);
                            } catch (IOException | RuntimeException e) {
                                // A single corrupt record must not prevent unrelated sessions or the server from starting.
                                log.warn("跳过无法恢复的命令记录 {}（{}）", file, e.getClass().getSimpleName());
                            }
                        }
                    } catch (IOException e) {
                        log.warn("无法读取会话命令目录 {}（{}）", directory, e.getClass().getSimpleName());
                    }
                }
            }
            prune(retainedLimit);
        } catch (IOException e) {
            throw new UncheckedIOException("恢复命令任务失败", e);
        }
    }

    private void recoverTask(Path file) throws IOException {
        if (Files.size(file) > 128 * 1024 || Files.isSymbolicLink(file)) {
            throw new IOException("Invalid task metadata file");
        }
        Task task = mapper.readValue(Files.readString(file), Task.class);
        if (task == null || task.startedAt() == null || task.command() == null || task.workdir() == null
            || WireEnum.parse(CommandTaskStatus.class, task.status()).isEmpty() || !file.equals(metadata(task))) {
            throw new IOException("Invalid task metadata");
        }
        Path.of(task.workdir());
        if (running(task)) {
            task = state(task, CommandTaskStatus.INTERRUPTED, task.pid(), null, Instant.now(),
                "服务重启，原进程所有权已失效；未按旧 PID 操作进程");
        }
        // 重启恢复的任务没有活进程，也就没有可写的输入通道
        Entry entry = new Entry(task, List.of(), false);
        Path logPath = logFile(task);
        if (Files.exists(logPath)) {
            entry.logBytes = Files.size(logPath);
            if (entry.logBytes > outputLimit || Files.isSymbolicLink(logPath)) {
                throw new IOException("Invalid task log");
            }
            try (Reader reader = Files.newBufferedReader(logPath, StandardCharsets.UTF_8)) {
                while (readCodePoint(reader) >= 0) {
                    entry.logPoints++;
                }
            }
        }
        persist(entry);
        entries.put(task.taskId(), entry);
    }

    private void prune(int keep) {
        List<Entry> oldest = entries.values().stream().filter(entry -> !running(entry.task))
            .sorted(Comparator.comparing(entry -> entry.task.startedAt())).toList();
        for (Entry entry : oldest) {
            if (entries.size() <= keep) {
                break;
            }
            try {
                Files.deleteIfExists(metadata(entry.task));
                Files.deleteIfExists(logFile(entry.task));
                entries.remove(entry.task.taskId(), entry);
            } catch (IOException e) {
                throw new UncheckedIOException("清理旧命令任务失败", e);
            }
        }
    }

    /**
     * 状态先落盘、后发布：观察者（get/list/日志读取方）看到某个状态时，对应元数据必然已写入完成，
     * 不会读到持久化过程中的临时文件，也不会在进程崩溃恢复时丢失已观察到的状态。
     */
    private void publish(Entry entry, Task next) {
        persist(entry, next);
        entry.task = next;
    }

    private void persist(Entry entry) {
        persist(entry, entry.task);
    }

    private void persist(Entry entry, Task task) {
        Path target = metadata(task);
        IOException failure = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            Path temp = null;
            try {
                Files.createDirectories(target.getParent());
                temp = Files.createTempFile(target.getParent(), "command-", ".tmp");
                Files.writeString(temp, mapper.writeValueAsString(task));
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (java.nio.file.FileSystemException e) {
                failure = e;
                if (attempt < 3) {
                    try {
                        Thread.sleep(25L * (attempt + 1));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (IOException e) {
                failure = e;
                break;
            } finally {
                if (temp != null) {
                    try {
                        Files.deleteIfExists(temp);
                    } catch (IOException cleanupFailure) {
                        log.debug("无法清理命令状态临时文件 {}：{}", temp, cleanupFailure.toString());
                    }
                }
            }
        }
        log.warn("命令状态持久化失败，继续保持进程生命周期：{}（{}）", task.taskId(), failure == null ? "interrupted" : failure.toString());
    }

    private Entry owned(String session, String id) {
        sessionDirectory(session);
        Entry entry = id == null ? null : entries.get(id);
        return entry != null && entry.task.sessionId().equals(session) ? entry : null;
    }

    /** 跨会话访问的任务所有者：明确指定了 ownerSessionId 就用它，否则仍停留在当前会话。 */
    private static String ownerOf(String sessionId, String ownerSessionId) {
        return ownerSessionId == null || ownerSessionId.isBlank() ? sessionId : ownerSessionId;
    }

    private Path sessionDirectory(String session) {
        if (session == null || !session.matches("[A-Za-z0-9_-]{1,160}")) {
            throw new IllegalArgumentException("非法会话标识符");
        }
        return storage.resolve(session);
    }

    private Path metadata(Task task) {
        if (task.taskId() == null || !task.taskId().matches("cmd_[A-Za-z0-9_-]{1,160}")) {
            throw new IllegalArgumentException("非法命令标识符");
        }
        return sessionDirectory(task.sessionId()).resolve(task.taskId() + ".json");
    }

    private Path logFile(Task task) {
        return metadata(task).resolveSibling(task.taskId() + ".log");
    }

    private static boolean running(Task task) {
        return CommandTaskStatus.active(task.status());
    }

    private static Path canonical(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    /** 已启动进程的实际 pid；进程不存在或平台不支持时返回 null。 */
    private static Long pid(Process process) {
        if (process == null) {
            return null;
        }
        try {
            return process.pid();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Task state(Task previous, CommandTaskStatus status, Long pid, Integer exit, Instant finished,
        String reason) {
        return new Task(previous.taskId(), previous.sessionId(), previous.command(), previous.workdir(), status.name(),
            pid, exit, previous.startedAt(), finished, previous.background(), previous.timeoutSeconds(), reason,
            previous.changeTracking());
    }

    private static int readCodePoint(Reader reader) throws IOException {
        int first = reader.read();
        if (first < 0 || !Character.isHighSurrogate((char)first)) {
            return first;
        }
        int second = reader.read();
        return second >= 0 && Character.isLowSurrogate((char)second) ? Character.toCodePoint((char)first, (char)second)
            : 0xfffd;
    }

    private static String redact(String text, List<String> secrets) {
        return SecretScrubber.scrub(new StreamingRedactor(secrets).accept(text, true));
    }

    private final class Entry {
        private volatile Task task;
        private final List<String> secrets;
        private final Object logLock = new Object();
        /** 串行化标准输入的写入与关闭，避免两次输入交错。 */
        private final Object inputLock = new Object();
        private final Map<Long, OwnedProcess> descendants = new ConcurrentHashMap<>();
        private volatile CommandTaskStatus stopStatus;
        private volatile String stopReason;
        private long stoppingAt;
        private long logBytes;
        private long logPoints;
        private final long startedNanos = System.nanoTime();
        private long lastOutputNanos;
        private Process process;
        private Future<?> reader;
        private boolean stdinOpen;

        private Entry(Task task, List<String> secrets, boolean interactive) {
            this.task = task;
            this.secrets = secrets;
            this.stdinOpen = interactive;
        }

        private void remember(ProcessHandle handle) {
            if (process != null && handle.pid() != process.pid()) {
                descendants.putIfAbsent(handle.pid(),
                    new OwnedProcess(handle, handle.info().startInstant().orElse(null)));
            }
        }

        private void discover() {
            process.descendants().forEach(this::remember);
            for (OwnedProcess child : descendants.values()) {
                if (child.alive()) {
                    child.handle.descendants().forEach(this::remember);
                }
            }
        }

        private boolean hasLiveDescendants() {
            return descendants.values().stream().anyMatch(OwnedProcess::alive);
        }
    }

    private record OwnedProcess(ProcessHandle handle, Instant start) {
        private boolean alive() {
            return isProcessRunning(handle)
                && (start == null || handle.info().startInstant().map(start::equals).orElse(false));
        }
    }

    /** Linux 容器的 PID 1 可能暂未回收孤儿；僵尸进程已退出，不能让任务一直停在 STOPPING。 */
    static boolean isProcessRunning(ProcessHandle handle) {
        if (!handle.isAlive()) {
            return false;
        }
        if (HostOs.isLinux()) {
            try {
                // 内核状态定义：https://docs.kernel.org/filesystems/proc.html
                String status = Files.readString(Path.of("/proc", Long.toString(handle.pid()), "status"));
                return !isZombieStatus(status);
            } catch (IOException | SecurityException ignored) {
                // 无 procfs 或无权限时保留标准 ProcessHandle 判断，不把未知进程当成已退出。
            }
        }
        return handle.isAlive();
    }

    static boolean isZombieStatus(String status) {
        return status.lines().anyMatch(line -> line.matches("State:\\s+Z(?:\\s.*)?"));
    }

    /**
     * 子进程输出的自适应解码：默认按 UTF-8 严格解码；一旦批内出现非法 UTF-8 字节，
     * 即判定该条流为 GBK（中文 Windows 控制台程序的常见情况），此后整条流按 GBK 续解。
     *
     * <p>切换不回改先前批次：非法字节出现的位置就是非 UTF-8 内容的起点，此前批次几乎总是
     * ASCII（两种编码下同文），极少存在「既合法 UTF-8 又实为 GBK」的先前内容，受损可接受。
     * 不完整的多字节序列跨批次缓存在 carry 里，不会误触发切换。
     */
    private static final class OutputDecoder {
        private final ByteArrayOutputStream carry = new ByteArrayOutputStream();
        private final CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        private final CharsetDecoder gbk = GBK.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
        private boolean gbkMode;

        String decode(byte[] chunk, int length, boolean endOfInput) {
            carry.write(chunk, 0, length);
            byte[] bytes = carry.toByteArray();
            carry.reset();
            if (!gbkMode) {
                CharBuffer out = CharBuffer.allocate(bytes.length + 1);
                ByteBuffer in = ByteBuffer.wrap(bytes);
                CoderResult result = utf8.decode(in, out, endOfInput);
                if (result.isUnderflow()) {
                    // 干净的 UTF-8；未消费的尾部是不完整序列，留到下一批继续
                    if (in.remaining() > 0) {
                        carry.write(bytes, bytes.length - in.remaining(), in.remaining());
                    }
                    out.flip();
                    return out.toString();
                }
                // 非法或不可映射字节：判定为 GBK 流，本批（含此前挂起字节）整体按 GBK 重解
                gbkMode = true;
            }
            StringBuilder text = new StringBuilder(bytes.length);
            ByteBuffer in = ByteBuffer.wrap(bytes);
            CharBuffer out = CharBuffer.allocate(Math.max(64, bytes.length + 1));
            while (in.hasRemaining()) {
                CoderResult result = gbk.decode(in, out, endOfInput);
                out.flip();
                text.append(out);
                out.clear();
                if (result.isUnderflow()) {
                    // 不完整的 GBK 双字节尾部留到下一批；流结束时由 REPLACE 兜底
                    if (!endOfInput && in.remaining() > 0) {
                        carry.write(bytes, bytes.length - in.remaining(), in.remaining());
                    }
                    break;
                }
                if (!result.isOverflow()) {
                    break;
                } // REPLACE 模式不应出现，防御性收尾
            }
            return text.toString();
        }
    }

    /** Retains only a suffix that could still become a secret; ordinary no-newline output is immediate. */
    private static final class StreamingRedactor {
        private final Map<Character, List<String>> byFirst = new HashMap<>();
        private final StringBuilder pending = new StringBuilder();

        private StreamingRedactor(List<String> secrets) {
            for (String secret : secrets) {
                byFirst.computeIfAbsent(secret.charAt(0), ignored -> new ArrayList<>()).add(secret);
            }
        }

        private String accept(String input, boolean end) {
            pending.append(input);
            StringBuilder output = new StringBuilder();
            int offset = 0;
            while (offset < pending.length()) {
                int match = 0;
                boolean partial = false;
                for (String secret : byFirst.getOrDefault(pending.charAt(offset), List.of())) {
                    int length = Math.min(secret.length(), pending.length() - offset);
                    boolean prefix = true;
                    for (int i = 0; i < length; i++) {
                        if (secret.charAt(i) != pending.charAt(offset + i)) {
                            prefix = false;
                            break;
                        }
                    }
                    if (prefix) {
                        if (length < secret.length()) {
                            partial = true;
                        } else {
                            match = Math.max(match, length);
                        }
                    }
                }
                if (partial && !end) {
                    break;
                }
                if (match > 0) {
                    output.append(REDACTED);
                    offset += match;
                    continue;
                }
                char value = pending.charAt(offset);
                if (Character.isHighSurrogate(value) && offset + 1 == pending.length() && !end) {
                    break;
                }
                output.append(value);
                offset++;
            }
            pending.delete(0, offset);
            return output.toString();
        }
    }
}
