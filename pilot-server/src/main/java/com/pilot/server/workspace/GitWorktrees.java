package com.pilot.server.workspace;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.pilot.core.tool.CommandTaskManager;
import com.pilot.server.config.PilotDataDir;
import com.pilot.server.session.RunRegistry;

/**
 * 按 Codex / Cursor 的方式开一个平行检出：从选定分支的当前提交建立 detached worktree，
 * 对话在那份目录里改，交回时把创建基线以来的全部差异应用到原来的检出。
 *
 * <p>不负责把一个子任务的提交自动交给下一个子任务。
 */
@Component
public class GitWorktrees {
    private final PilotDataDir dataDir;
    private RunRegistry registry;
    private CommandTaskManager commands;

    @org.springframework.beans.factory.annotation.Autowired
    public GitWorktrees(PilotDataDir dataDir, RunRegistry registry, CommandTaskManager commands) {
        this(dataDir, registry);
        this.commands = commands;
    }

    public GitWorktrees(PilotDataDir dataDir, RunRegistry registry) {
        this(dataDir);
        this.registry = registry;
    }

    public GitWorktrees(PilotDataDir dataDir) {
        this.dataDir = dataDir;
    }

    /** 只读展示执行位置；尚无提交的分支也能识别，detached HEAD 明示提交。 */
    public String currentBranch(Path workspace) {
        if (!Files.isDirectory(workspace)) {
            return "";
        }
        Exec branch = git(workspace, "symbolic-ref", "--short", "-q", "HEAD");
        if (branch.exit == 0) {
            return branch.output.strip();
        }
        Exec commit = git(workspace, "rev-parse", "--short", "HEAD");
        return commit.exit == 0 ? "detached @ " + commit.output.strip() : "";
    }

    public Created create(Path workspace, String branch) {
        Path root = toplevel(workspace);
        String selected = branch == null ? "" : branch.strip();
        if (selected.isEmpty()) {
            Exec current = git(root, "rev-parse", "--abbrev-ref", "HEAD");
            if (current.exit != 0) {
                throw new IllegalArgumentException("读不到当前分支：" + current.output.strip());
            }
            selected = current.output.strip();
            if ("HEAD".equals(selected)) {
                selected = "HEAD";
            }
        }
        Exec commit = git(root, "rev-parse", "--verify", selected + "^{commit}");
        if (commit.exit != 0) {
            throw new IllegalArgumentException("分支不存在：" + selected);
        }
        String id = "wt_" + UUID.randomUUID().toString().substring(0, 8);
        Path destination = location(root, id);
        try {
            Files.createDirectories(destination.getParent());
        } catch (IOException e) {
            throw new IllegalArgumentException("无法建立 worktree 目录：" + e.getMessage());
        }
        Exec added = git(root, "worktree", "add", "--detach", destination.toString(), commit.output.strip());
        if (added.exit != 0) {
            throw new IllegalArgumentException("创建 worktree 失败：" + added.output.strip());
        }
        Meta meta = new Meta(id, destination.toString(), root.toString(), selected, commit.output.strip());
        try {
            store(meta);
        } catch (RuntimeException failure) {
            Exec removed = git(root, "worktree", "remove", "--force", destination.toString());
            if (removed.exit != 0) {
                failure.addSuppressed(new IllegalStateException(removed.output));
            }
            try {
                Files.deleteIfExists(registryDir().resolve(meta.id + ".properties"));
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        return new Created(meta.id, meta.path, meta.base, meta.branch, meta.commit);
    }

    public Optional<Meta> findByPath(Path path) {
        if (path == null) {
            return Optional.empty();
        }
        Path wanted = path.toAbsolutePath().normalize();
        try (Stream<Path> files = Files.list(registryDir())) {
            for (Path file : files.filter(item -> item.getFileName().toString().endsWith(".properties")).toList()) {
                Meta meta = load(file);
                if (meta != null && Path.of(meta.path).toAbsolutePath().normalize().equals(wanted)) {
                    return Optional.of(meta);
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** 仅读取 Pilot 登记文件，工作树内容由备份范围决定。 */
    public List<Meta> registrations() throws IOException {
        Path directory = dataDir.file("worktrees");
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var files = Files.list(directory)) {
            List<Meta> result = new ArrayList<>();
            for (Path file : files.filter(item -> item.getFileName().toString().matches("wt_[a-f0-9]{8}\\.properties"))
                .sorted().toList()) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("worktree 登记不可读取：" + file);
                }
                Meta meta = load(file);
                if (meta == null || !file.getFileName().toString().equals(meta.id() + ".properties")) {
                    throw new IOException("worktree 登记无效：" + file);
                }
                result.add(meta);
            }
            return List.copyOf(result);
        }
    }

    public void restoreRegistration(Meta meta) {
        if (!meta.id().matches("wt_[a-f0-9]{8}")) {
            throw new IllegalArgumentException("worktree 登记标识无效");
        }
        store(meta);
    }

    /** 完整交回包：临时 Git index 捕获相对创建基线的提交、工作区修改和新增文件。 */
    public record Preview(String base, String path, String commit, List<String> files, String diff, boolean truncated,
        String revision, String conflict) {
    }

    private record Patch(Meta meta, List<String> files, String diff, String revision, String conflict) {
    }

    public Preview preview(Path worktree) {
        Patch patch = patch(worktree);
        return new Preview(patch.meta.base, patch.meta.path, patch.meta.commit, patch.files,
            patch.diff.substring(0, Math.min(200_000, patch.diff.length())), patch.diff.length() > 200_000,
            patch.revision, patch.conflict);
    }

    public Handoff handoff(Path worktree) {
        return handoff(worktree, null);
    }

    public synchronized Handoff handoff(Path worktree, String expectedRevision) {
        Meta meta = findByPath(worktree).orElseThrow(() -> new IllegalArgumentException("这不是 worktree 对话"));
        String operation = "handoff_" + UUID.randomUUID();
        try {
            if (registry != null && (!registry.lockWorkspace(operation, Path.of(meta.path))
                || !registry.lockWorkspace(operation, Path.of(meta.base)))) {
                throw new IllegalStateException("执行目录或原目录仍有任务运行，请等待结束后交回");
            }
            if (commands != null && (commands.hasRunningInWorkspace(Path.of(meta.path))
                || commands.hasRunningInWorkspace(Path.of(meta.base)))) {
                throw new IllegalStateException("执行目录或原目录仍有后台命令运行，请结束后交回");
            }
            Patch patch = patch(worktree);
            if (expectedRevision != null && !expectedRevision.equals(patch.revision)) {
                throw new IllegalStateException("预览后文件已变化，请重新预览再交回");
            }
            if (!patch.conflict.isBlank()) {
                throw new IllegalArgumentException(patch.conflict);
            }
            if (!patch.diff.isBlank()) {
                Exec applied =
                    command(Path.of(meta.base), Map.of(), patch.diff, "apply", "--binary", "--whitespace=nowarn");
                if (applied.exit != 0) {
                    throw new IllegalArgumentException("交回失败，请检查原目录：" + applied.output.strip());
                }
            }
            return new Handoff(meta.base, patch.files.size(), patch.files.isEmpty() ? "没有需要交回的改动" : "改动已交回原目录，独立工作区保留");
        } finally {
            if (registry != null) {
                registry.unregister(operation);
            }
        }
    }

    private Patch patch(Path worktree) {
        Meta meta = findByPath(worktree).orElseThrow(() -> new IllegalArgumentException("这不是 worktree 对话"));
        Path copy = Path.of(meta.path);
        Path base = Path.of(meta.base);
        Path index = null;
        try {
            index = Files.createTempFile("pilot-handoff-", ".index");
            Files.delete(index); // Git 不接受空文件作为 index。
            var env = Map.of("GIT_INDEX_FILE", index.toAbsolutePath().toString());
            checked(command(copy, env, null, "read-tree", meta.commit));
            checked(command(copy, env, null, "add", "--all", "--", "."));
            String modes = checked(command(copy, env, null, "diff", "--cached", "--raw", meta.commit, "--"));
            if (Pattern.compile("(?m)^:(?:160000 [0-9]{6}|[0-9]{6} 160000) ").matcher(modes).find()) {
                throw new IllegalArgumentException("交回涉及子模块，请在 Git 中手动处理");
            }
            String diff =
                checked(command(copy, env, null, "diff", "--cached", "--binary", "--full-index", meta.commit, "--"));
            String names =
                checked(command(copy, env, null, "diff", "--cached", "--name-only", "-z", meta.commit, "--"));
            List<String> files = Arrays.stream(names.split("\\u0000")).filter(n -> !n.isEmpty()).toList();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(diff.getBytes(StandardCharsets.UTF_8));
            digest.update(checked(git(base, "rev-parse", "HEAD")).getBytes(StandardCharsets.UTF_8));
            for (String name : files) {
                Path destination = base.resolve(name).normalize();
                if (!destination.startsWith(base.normalize())) {
                    throw new IllegalArgumentException("拒绝越界路径：" + name);
                }
                Path parent = destination;
                while (parent != null && parent.startsWith(base)) {
                    if (Files.isSymbolicLink(parent)) {
                        throw new IllegalArgumentException("交回涉及符号链接，请手动处理：" + name);
                    }
                    parent = parent.getParent();
                }
                if (Files.isSymbolicLink(copy.resolve(name))) {
                    throw new IllegalArgumentException("交回涉及符号链接，请手动处理：" + name);
                }
                digest.update(name.getBytes(StandardCharsets.UTF_8));
                digest.update((byte)0);
                if (Files.isRegularFile(destination)) {
                    digest.update((byte)1);
                    try (var stream = Files.newInputStream(destination)) {
                        byte[] bytes = new byte[8192];
                        int size;
                        while ((size = stream.read(bytes)) >= 0) {
                            digest.update(bytes, 0, size);
                        }
                    }
                } else {
                    digest.update((byte)(Files.exists(destination) ? 2 : 3));
                }
            }
            Exec check = diff.isBlank() ? new Exec(0, "")
                : command(base, Map.of(), diff, "apply", "--check", "--binary", "--whitespace=nowarn");
            return new Patch(meta, files, diff, HexFormat.of().formatHex(digest.digest()),
                check.exit == 0 ? "" : "原目录存在冲突，尚未交回任何改动：" + check.output.strip());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("无法准备交回预览：" + e.getMessage(), e);
        } finally {
            if (index != null) {
                try {
                    Files.deleteIfExists(index);
                    Files.deleteIfExists(Path.of(index + ".lock"));
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String checked(Exec result) {
        if (result.exit != 0) {
            throw new IllegalArgumentException("Git 操作失败：" + result.output.strip());
        }
        return result.output;
    }

    public void remove(Path worktree) {
        Meta meta = findByPath(worktree).orElseThrow(() -> new IllegalArgumentException("这不是 worktree 对话"));
        checked(git(Path.of(meta.base), "worktree", "remove", "--force", meta.path));
        try {
            Files.deleteIfExists(registryDir().resolve(meta.id + ".properties"));
        } catch (IOException e) {
            throw new IllegalArgumentException("worktree 已移除，但登记没删掉：" + e.getMessage());
        }
    }

    private Path location(Path root, String id) {
        Path inside = dataDir.file("worktrees", id);
        if (!inside.startsWith(root)) {
            return inside;
        }
        String key = Integer.toHexString(root.toString().hashCode());
        return Path.of(System.getProperty("user.home"), ".pilot", "worktrees", key, id);
    }

    private Path registryDir() {
        Path dir = dataDir.file("worktrees");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalArgumentException("无法建立 worktree 登记目录：" + e.getMessage());
        }
        return dir;
    }

    private void store(Meta meta) {
        Properties properties = new Properties();
        properties.setProperty("id", meta.id);
        properties.setProperty("path", meta.path);
        properties.setProperty("base", meta.base);
        properties.setProperty("branch", meta.branch);
        properties.setProperty("commit", meta.commit);
        Path file = registryDir().resolve(meta.id + ".properties");
        try (OutputStreamWriter writer = new OutputStreamWriter(Files.newOutputStream(file), StandardCharsets.UTF_8)) {
            properties.store(writer, "pilot worktree");
        } catch (IOException e) {
            throw new IllegalArgumentException("无法登记 worktree：" + e.getMessage());
        }
    }

    private Meta load(Path file) {
        Properties properties = new Properties();
        try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            return null;
        }
        String id = properties.getProperty("id");
        String path = properties.getProperty("path");
        String base = properties.getProperty("base");
        if (id == null || path == null || base == null) {
            return null;
        }
        return new Meta(id, path, base, properties.getProperty("branch", ""), properties.getProperty("commit", ""));
    }

    private static Path toplevel(Path workspace) {
        Exec exec = git(workspace.toAbsolutePath().normalize(), "rev-parse", "--show-toplevel");
        if (exec.exit != 0) {
            throw new IllegalArgumentException("当前目录不是 git 仓库");
        }
        return Path.of(exec.output.strip());
    }

    private static Exec git(Path directory, String... args) {
        return command(directory, Map.of(), null, args);
    }

    /** 输出落临时文件后再等进程，避免输出管道填满或 readAllBytes 绕过超时。 */
    private static Exec command(Path directory, Map<String, String> env, String input, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Path output = null;
        Process process = null;
        try {
            output = Files.createTempFile("pilot-git-", ".out");
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(output.toFile());
            builder.environment().putAll(env);
            process = builder.start();
            Process child = process;
            Thread writer = Thread.ofVirtual().start(() -> {
                try (var stream = child.getOutputStream()) {
                    if (input != null) {
                        stream.write(input.getBytes(StandardCharsets.UTF_8));
                    }
                } catch (IOException ignored) {
                }
            });
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Exec(-1, "git 超时");
            }
            writer.join(1000);
            if (Files.size(output) > 20_000_000) {
                return new Exec(-1, "改动超过 20 MB，请拆分或手动交回");
            }
            return new Exec(process.exitValue(), Files.readString(output));
        } catch (IOException e) {
            return new Exec(-1, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Exec(-1, "git 被中断");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                }
            }
        }
    }

    public record Created(String id, String path, String base, String branch, String commit) {
    }

    public record Meta(String id, String path, String base, String branch, String commit) {
    }

    public record Handoff(String base, int files, String note) {
    }

    private record Exec(int exit, String output) {
    }

    /** 测试清理用，避免临时仓库留下 worktree 登记。 */
    static void deleteTree(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }
}
