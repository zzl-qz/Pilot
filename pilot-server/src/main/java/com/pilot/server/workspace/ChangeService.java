package com.pilot.server.workspace;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 变更审阅：读取工作区的 git 状态与 diff，供界面展示「这次到底改了什么」。
 * 只读操作，不修改仓库；工作区不是 git 仓库时返回 {@code gitRepository=false}。
 */
@Component
public class ChangeService {
    private static final int MAX_DIFF_CHARS = 200_000;
    private static final int MAX_FILE_DIFF_CHARS = 60_000;
    private static final int MAX_UNTRACKED_LINES = 400;
    private static final long MAX_UNTRACKED_BYTES = 512 * 1024;
    private static final int MAX_FILES = 500;
    private static final long TIMEOUT_SECONDS = 15;
    /** 单个 git 命令最多收集这么多字节；超出部分读掉但不保留，避免堆被打爆。 */
    private static final int MAX_OUTPUT_BYTES = 4 * 1024 * 1024;

    /**
     * 工作区里的一个改动文件。
     *
     * @param added   相对 HEAD 新增的行数；未跟踪文件按当前内容整文件计
     * @param removed 相对 HEAD 删除的行数
     */
    public record FileChange(String status, String path, int added, int removed) {
    }

    /**
     * @param diff 未暂存改动的 unified diff；仓库不是 git 仓库时为空字符串
     */
    public record ChangeReport(String workspace, boolean gitRepository, List<FileChange> files,
                               String diff, boolean truncated, String error) {
    }

    /**
     * 单个文件在本轮运行里的改动。
     *
     * @param status 单字符状态：{@code M}/{@code A}/{@code D}/{@code R}/{@code U}/{@code ??}
     * @param added  新增行数；非 git 仓库或二进制文件为 0，不能据此判断「没改动」
     * @param diff   该文件的 unified diff；无内容可展示时为空字符串
     */
    public record RunFileChange(String path, String status, int added, int removed, String diff) {
    }

    /** 按运行汇总的改动清单，供对话区像 Codex 那样列出「本轮改了哪些文件」。 */
    public record RunChangeReport(String runId, String workspace, boolean gitRepository,
                                  List<RunFileChange> files, int added, int removed,
                                  boolean truncated, String error) {
    }

    public ChangeReport inspect(Path workspace) {
        String root = workspace.toString();
        if (!Files.isDirectory(workspace.resolve(".git"))) {
            return new ChangeReport(root, false, List.of(), "", false, null);
        }

        String status = exec(workspace, List.of("git", "status", "--porcelain=v1", "-uall"));
        // 与运行级保持一致：相对 HEAD 取补丁，已暂存的改动才不会在审阅面板里消失
        boolean head = hasHead(workspace);
        var diffCommand = new ArrayList<>(List.of("git", "-c", "core.quotePath=false", "diff",
                "--no-color", "--no-ext-diff"));
        if (head) {
            diffCommand.add("HEAD");
        }
        diffCommand.add("--");
        diffCommand.add(".");
        String diff = stripNoise(exec(workspace, diffCommand));
        // 行数用 numstat，不从可能被截断的补丁正文里数，否则大改动会显示成 +0 −0
        Map<String, int[]> stats = numstat(workspace, head);

        var files = new ArrayList<FileChange>();
        boolean truncated = false;
        for (String line : status.lines().toList()) {
            if (line.length() < 3) {
                continue;
            }
            if (files.size() >= MAX_FILES) {
                truncated = true;
                break;
            }
            String code = line.substring(0, 2).strip();
            String path = line.substring(3).strip();
            int renameMark = path.indexOf(" -> ");
            if (renameMark > 0) {
                path = path.substring(renameMark + 4);
            }
            if (code.isEmpty()) {
                code = "?";
            }
            int[] stat = stats.get(path);
            if (stat == null && countsAsWholeFile(code)) {
                stat = new int[]{countTextLines(workspace.resolve(path)), 0};
            }
            if (stat == null) {
                stat = new int[]{0, 0};
            }
            files.add(new FileChange(code, path, stat[0], stat[1]));
        }

        boolean diffTruncated = diff.length() > MAX_DIFF_CHARS;
        String body = diffTruncated ? diff.substring(0, MAX_DIFF_CHARS) + "\n… diff 已截断 …" : diff;
        return new ChangeReport(root, true, List.copyOf(files), body, truncated || diffTruncated, null);
    }

    /**
     * 只复查「本轮运行写过的文件」：给出逐文件状态、增删行数与 diff。
     *
     * <p>路径过滤重新做一遍安全校验：清单来自工具参数，不能直接拼进 git pathspec 或文件系统读取。
     * 工作区不是 git 仓库时仍能列出文件，但拿不到行级统计与 diff。
     */
    public RunChangeReport inspectRun(String runId, Path workspace, List<String> paths) {
        Path root = workspace.toAbsolutePath().normalize();
        boolean gitRepository = Files.isDirectory(root.resolve(".git"));

        List<String> wanted = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : paths == null ? List.<String>of() : paths) {
            String path = safeRelative(raw);
            if (path != null && seen.add(path)) {
                wanted.add(path);
            }
        }
        if (wanted.isEmpty()) {
            return new RunChangeReport(runId, root.toString(), gitRepository, List.of(), 0, 0, false, null);
        }
        if (!gitRepository) {
            var files = new ArrayList<RunFileChange>();
            for (String path : wanted) {
                String status = Files.isRegularFile(root.resolve(path)) ? "M" : "D";
                files.add(new RunFileChange(path, status, 0, 0, ""));
            }
            return new RunChangeReport(runId, root.toString(), false, List.copyOf(files), 0, 0, false, null);
        }

        boolean head = hasHead(root);
        StatusScan scan = statuses(root);
        Map<String, String> statuses = scan.codes();
        // 重命名要连同旧路径一起交给 git，否则 pathspec 会把旧路径排除，重命名检测失效
        Set<String> diffPaths = new LinkedHashSet<>(wanted);
        diffPaths.addAll(scan.renameSources());
        Map<String, String> chunks = diffChunks(root, List.copyOf(diffPaths), head);

        var files = new ArrayList<RunFileChange>();
        int addedTotal = 0;
        int removedTotal = 0;
        int budget = MAX_DIFF_CHARS;
        boolean truncated = false;

        for (String path : wanted) {
            if (files.size() >= MAX_FILES) {
                truncated = true;
                break;
            }
            String status = statuses.get(path);
            String chunk = chunks.get(path);
            if (chunk != null && isBinaryPatch(chunk)) {
                // 二进制补丁只有一行「Binary files … differ」，没有可读内容
                chunk = "";
            }
            int[] stat;
            if (chunk != null) {
                stat = countLines(chunk);
            } else if ("??".equals(status) || (!head && "A".equals(status))) {
                // 新增文件不在 git diff 里（或仓库还没有 HEAD 可比），按当前内容合成一份「全新增」的 diff
                SyntheticDiff synthetic = untrackedDiff(root.resolve(path), path);
                chunk = synthetic.text();
                stat = new int[]{synthetic.added(), 0};
                truncated = truncated || synthetic.truncated();
            } else if (status == null) {
                // 写过但内容又回到 HEAD 一致，或文件已被删除，不算本轮可见改动
                continue;
            } else {
                // 纯重命名、二进制等没有文本补丁的场景：文件仍要列出来
                chunk = "";
                stat = new int[]{0, 0};
            }
            String text = chunk;
            addedTotal += stat[0];
            removedTotal += stat[1];

            if (text.length() > MAX_FILE_DIFF_CHARS) {
                text = text.substring(0, MAX_FILE_DIFF_CHARS) + "\n… 该文件 diff 已截断 …";
                truncated = true;
            }
            String diff = text;
            if (diff.length() > budget) {
                diff = diff.substring(0, Math.max(0, budget)) + "\n… diff 已截断 …";
                truncated = true;
                budget = 0;
            } else {
                budget -= diff.length();
            }
            files.add(new RunFileChange(path, status == null ? "M" : status, stat[0], stat[1], diff));
        }

        return new RunChangeReport(runId, root.toString(), true, List.copyOf(files),
                addedTotal, removedTotal, truncated, null);
    }

    /** 把工具给的展示路径收敛成工作区内的相对路径；越界或绝对路径一律拒绝。 */
    private static String safeRelative(String raw) {
        if (raw == null) {
            return null;
        }
        String path = raw.replace('\\', '/').strip();
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        if (path.isEmpty() || path.startsWith("/") || path.matches("^[A-Za-z]:.*")) {
            return null;
        }
        for (String part : path.split("/")) {
            if ("..".equals(part)) {
                return null;
            }
        }
        return path;
    }

    /** @param renameSources 重命名的旧路径，取 diff 时要一并带上 */
    private record StatusScan(Map<String, String> codes, List<String> renameSources) {
    }

    /**
     * 整个仓库的 porcelain 状态，再按期望路径过滤。
     *
     * <p>不能把 pathspec 传给 {@code git status}：一旦限定路径，git 会关掉重命名检测，
     * 重命名就退化成「删除 + 新增」，状态和 diff 都对不上。
     */
    private static StatusScan statuses(Path root) {
        String output = exec(root, List.of("git", "status", "--porcelain=v1", "-z", "-uall"));
        Map<String, String> result = new LinkedHashMap<>();
        List<String> renameSources = new ArrayList<>();
        String[] tokens = output.split("\u0000", -1);
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.length() < 4) {
                continue;
            }
            String raw = token.substring(0, 2).strip();
            result.put(token.substring(3), statusCode(raw));
            if (raw.indexOf('R') >= 0 || raw.indexOf('C') >= 0) {
                // 重命名/复制条目后面还跟着原路径
                if (i + 1 < tokens.length && !tokens[i + 1].isEmpty()) {
                    renameSources.add(tokens[++i]);
                }
            }
        }
        return new StatusScan(result, List.copyOf(renameSources));
    }

    /** 两列状态合并成一个：重命名 &gt; 新增 &gt; 删除 &gt; 冲突 &gt; 修改，便于界面单字符展示。 */
    private static String statusCode(String raw) {
        if (raw.isEmpty()) {
            return "?";
        }
        if (raw.indexOf('R') >= 0) {
            return "R";
        }
        if (raw.indexOf('A') >= 0) {
            return "A";
        }
        if (raw.indexOf('D') >= 0) {
            return "D";
        }
        if (raw.indexOf('U') >= 0) {
            return "U";
        }
        if (raw.indexOf('?') >= 0) {
            return "??";
        }
        return raw.strip();
    }

    /** 一次 git diff 取全部文件的补丁，再按文件头切开；路径用字面量 pathspec，避免被当成通配符。 */
    private static Map<String, String> diffChunks(Path root, List<String> paths, boolean head) {
        var command = new ArrayList<>(List.of("git", "-c", "core.quotePath=false", "diff",
                "--no-color", "--no-ext-diff", "--unified=3"));
        // 相对 HEAD 比较：已暂存（git add）的改动也要算进来；仓库还没有提交时退回工作区 diff
        if (head) {
            command.add("HEAD");
        }
        String output = exec(root, withPathspec(command, paths));
        Map<String, String> chunks = new LinkedHashMap<>();
        String current = null;
        StringBuilder buffer = new StringBuilder();
        for (String line : output.split("\n", -1)) {
            if (line.startsWith("diff --git ")) {
                if (current != null) {
                    chunks.put(current, buffer.toString());
                }
                current = matchPath(line, paths);
                buffer.setLength(0);
                if (current == null) {
                    continue;
                }
            }
            if (current != null) {
                buffer.append(line).append('\n');
            }
        }
        if (current != null) {
            chunks.put(current, buffer.toString());
        }
        return chunks;
    }

    /**
     * 把 {@code diff --git} 头对回期望路径：同名改动直接比字符串，
     * 重命名/复制则是 {@code a/旧路径 b/新路径}，取 {@code b/} 之后的部分比对。
     */
    private static String matchPath(String header, List<String> paths) {
        for (String path : paths) {
            if (header.equals("diff --git a/" + path + " b/" + path)) {
                return path;
            }
        }
        int marker = header.lastIndexOf(" b/");
        if (marker < 0) {
            return null;
        }
        String target = header.substring(marker + 3);
        for (String path : paths) {
            if (path.equals(target)) {
                return path;
            }
        }
        return null;
    }

    /**
     * 每个改动文件的增删行数。有提交时相对 HEAD（暂存和工作区都算），
     * 还没有提交时只看暂存区。未跟踪文件不在这份输出里，调用方另行数行。
     */
    private static Map<String, int[]> numstat(Path root, boolean head) {
        var command = new ArrayList<>(List.of("git", "-c", "core.quotePath=false", "diff",
                "--numstat", "--no-color", "--no-ext-diff"));
        if (head) {
            command.add("HEAD");
        } else {
            command.add("--cached");
        }
        command.add("--");
        command.add(".");
        return parseNumstat(exec(root, command));
    }

    private static Map<String, int[]> parseNumstat(String output) {
        Map<String, int[]> result = new LinkedHashMap<>();
        if (output == null || output.isBlank()) {
            return result;
        }
        for (String line : output.lines().toList()) {
            String[] parts = line.split("\t", 3);
            if (parts.length < 3 || parts[2].isBlank()) {
                continue;
            }
            result.put(renameDestination(parts[2].strip()), new int[]{parseStat(parts[0]), parseStat(parts[1])});
        }
        return result;
    }

    /** 二进制文件的 numstat 是 {@code -}，按 0 行计，避免界面把「没法数」显示成异常。 */
    private static int parseStat(String raw) {
        if (raw == null || raw.isBlank() || "-".equals(raw)) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * numstat 的重命名有两种写法：{@code old => new}，或 {@code dir/{old => new}.java}。
     * 界面列表用的是新路径，这里收成同一个键。
     */
    private static String renameDestination(String path) {
        int arrow = path.indexOf(" => ");
        if (arrow < 0) {
            return path;
        }
        int open = path.indexOf('{');
        int close = path.lastIndexOf('}');
        if (open >= 0 && close > arrow) {
            String inner = path.substring(open + 1, close);
            int innerArrow = inner.indexOf(" => ");
            String dest = innerArrow < 0 ? inner : inner.substring(innerArrow + 4);
            return path.substring(0, open) + dest + path.substring(close + 1);
        }
        return path.substring(arrow + 4);
    }

    /** 未跟踪或新增、且 numstat 里没有这一条时，按当前文件内容整份计为新增。 */
    private static boolean countsAsWholeFile(String code) {
        return code.indexOf('?') >= 0 || code.indexOf('A') >= 0;
    }

    /** 未跟踪文件的行数。过大或二进制不硬读，避免审阅面板被一个大文件拖住。 */
    private static int countTextLines(Path absolute) {
        try {
            if (!Files.isRegularFile(absolute) || Files.size(absolute) > MAX_UNTRACKED_BYTES || isBinary(absolute)) {
                return 0;
            }
            try (var stream = Files.lines(absolute, StandardCharsets.UTF_8)) {
                return (int) Math.min(Integer.MAX_VALUE, stream.count());
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            return 0;
        }
    }

    private static boolean hasHead(Path root) {
        return !exec(root, List.of("git", "rev-parse", "--verify", "--quiet", "HEAD")).isBlank();
    }

    private static List<String> withPathspec(List<String> command, List<String> paths) {
        var full = new ArrayList<>(command);
        full.add("--");
        for (String path : paths) {
            full.add(":(literal)" + path);
        }
        return full;
    }

    /** git 对二进制文件只给一行 {@code Binary files a/x and b/x differ}，没有可展示的文本。 */
    private static boolean isBinaryPatch(String chunk) {
        return chunk.startsWith("Binary files ") || chunk.contains("\nBinary files ");
    }

    /** 文本文件里出现 NUL 一律按二进制处理：不合成 diff，避免把乱码塞进对话区。 */
    private static boolean isBinary(Path path) {
        try (var input = Files.newInputStream(path)) {
            byte[] sample = input.readNBytes(8192);
            for (byte value : sample) {
                if (value == 0) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    /** 合成补丁：{@code added} 是文件的真实行数，{@code text} 只保留前 {@value #MAX_UNTRACKED_LINES} 行。 */
    private record SyntheticDiff(String text, int added, boolean truncated) {
    }

    /** 新增文件：git diff 看不到它，按当前内容合成一份全「+」的补丁。 */
    private static SyntheticDiff untrackedDiff(Path absolute, String path) {
        if (!Files.isRegularFile(absolute) || isBinary(absolute)) {
            return new SyntheticDiff("", 0, false);
        }
        String text;
        try {
            if (Files.size(absolute) > MAX_UNTRACKED_BYTES) {
                return new SyntheticDiff("", 0, true);
            }
            byte[] bytes = Files.readAllBytes(absolute);
            if (new String(bytes, StandardCharsets.ISO_8859_1).indexOf('\0') >= 0) {
                return new SyntheticDiff("", 0, false);
            }
            text = new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new SyntheticDiff("", 0, false);
        }
        List<String> lines = text.lines().toList();
        List<String> shown = lines.subList(0, Math.min(lines.size(), MAX_UNTRACKED_LINES));
        StringBuilder out = new StringBuilder();
        out.append("diff --git a/").append(path).append(" b/").append(path).append('\n');
        out.append("new file mode 100644\n");
        out.append("--- /dev/null\n");
        out.append("+++ b/").append(path).append('\n');
        out.append("@@ -0,0 +1,").append(lines.size()).append(" @@\n");
        for (String line : shown) {
            out.append('+').append(line).append('\n');
        }
        boolean truncated = lines.size() > shown.size();
        if (truncated) {
            out.append("… 已省略 ").append(lines.size() - shown.size()).append(" 行 …\n");
        }
        return new SyntheticDiff(out.toString(), lines.size(), truncated);
    }

    /** 只数 hunk 内的增删行：{@code ---}/{@code +++} 是文件头，源码里以 {@code --} 开头的内容不能被当成头。 */
    private static int[] countLines(String diff) {
        int added = 0;
        int removed = 0;
        if (diff == null || diff.isEmpty()) {
            return new int[]{0, 0};
        }
        boolean inHunk = false;
        for (String line : diff.split("\n", -1)) {
            if (line.startsWith("diff --git ")) {
                inHunk = false;
                continue;
            }
            if (line.startsWith("@@")) {
                inHunk = true;
                continue;
            }
            if (!inHunk) {
                continue;
            }
            if (line.startsWith("+")) {
                added++;
            } else if (line.startsWith("-")) {
                removed++;
            }
        }
        return new int[]{added, removed};
    }

    /** 去掉 git 输出里与 diff 无关的告警行（例如 Windows 的 LF/CRLF 提示）。 */
    private static String stripNoise(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("warning: ")) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(line);
        }
        return out.toString();
    }

    /**
     * 跑一个 git 子进程并收集输出。
     *
     * <p>边读边限量：{@code readAllBytes} 会先把整份输出读进内存再截断，仓库很大时
     * 一次 {@code git diff} 就能把堆撑爆。超过上限后继续消费但不存储，避免管道写满卡死子进程。
     *
     * <p>超时由独立的看门狗线程负责杀进程：读流本身是阻塞的，只在读完之后再 {@code waitFor}
     * 会让超时形同虚设。
     */
    private static String exec(Path workspace, List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workspace.toFile());
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            process.getOutputStream().close();
            var timedOut = new java.util.concurrent.atomic.AtomicBoolean(false);
            Thread watchdog = Thread.ofVirtual().name("pilot-git-watchdog").start(() -> {
                try {
                    if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        timedOut.set(true);
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            var collected = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            try (var stream = process.getInputStream()) {
                int read;
                while ((read = stream.read(buffer)) > 0) {
                    if (collected.size() < MAX_OUTPUT_BYTES) {
                        collected.write(buffer, 0, Math.min(read, MAX_OUTPUT_BYTES - collected.size()));
                    }
                }
            }
            watchdog.interrupt();
            process.waitFor(1, TimeUnit.SECONDS);
            if (timedOut.get()) {
                return "";
            }
            return collected.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }
}
