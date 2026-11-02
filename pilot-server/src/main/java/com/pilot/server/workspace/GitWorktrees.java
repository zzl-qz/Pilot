package com.pilot.server.workspace;

import com.pilot.server.config.PilotDataDir;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 按 Codex / Cursor 的方式开一个平行检出：从选定分支的当前提交建立 detached worktree，
 * 对话在那份目录里改，交回时把未提交的差异应用到原来的检出。
 *
 * <p>不负责把一个子任务的提交自动交给下一个子任务。
 */
@Component
public class GitWorktrees {
    private final PilotDataDir dataDir;

    public GitWorktrees(PilotDataDir dataDir) {
        this.dataDir = dataDir;
    }

    /** 只读展示执行位置；尚无提交的分支也能识别，detached HEAD 明示提交。 */
    public String currentBranch(Path workspace) {
        if (!Files.isDirectory(workspace)) return "";
        Exec branch = git(workspace, "symbolic-ref", "--short", "-q", "HEAD");
        if (branch.exit == 0) return branch.output.strip();
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
        store(meta);
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

    /** 把 worktree 里相对起点提交的未提交改动应用到原来的检出。worktree 本身留着。 */
    public Handoff handoff(Path worktree) {
        Meta meta = findByPath(worktree).orElseThrow(() -> new IllegalArgumentException("这不是 worktree 对话"));
        Path copy = Path.of(meta.path);
        Path base = Path.of(meta.base);
        Exec diff = git(copy, "diff", "--binary", "HEAD");
        if (diff.exit != 0) {
            throw new IllegalArgumentException("读不到 worktree 差异：" + diff.output.strip());
        }
        int files = 0;
        if (!diff.output.isBlank() && !apply(base, diff.output)) {
            throw new IllegalArgumentException("交回失败，本地目录没有改动");
        }
        if (!diff.output.isBlank()) {
            files++;
        }
        Exec untracked = git(copy, "ls-files", "--others", "--exclude-standard");
        if (untracked.exit != 0) {
            throw new IllegalArgumentException("读不到未跟踪文件：" + untracked.output.strip());
        }
        for (String relative : untracked.output.split("\n")) {
            if (relative.isBlank()) {
                continue;
            }
            Path from = copy.resolve(relative).normalize();
            Path to = base.resolve(relative).normalize();
            if (!to.startsWith(base)) {
                throw new IllegalArgumentException("拒绝交回越界路径：" + relative);
            }
            if (Files.exists(to)) {
                throw new IllegalArgumentException("本地已有文件，未覆盖：" + relative);
            }
            try {
                Files.createDirectories(to.getParent());
                Files.copy(from, to);
            } catch (IOException e) {
                throw new IllegalArgumentException("复制未跟踪文件失败：" + relative);
            }
            files++;
        }
        String note = files == 0 ? "没有需要交回的改动" : "已把 worktree 里的未提交改动交回本地目录";
        return new Handoff(base.toString(), files, note);
    }

    public void remove(Path worktree) {
        Meta meta = findByPath(worktree).orElseThrow(() -> new IllegalArgumentException("这不是 worktree 对话"));
        git(Path.of(meta.base), "worktree", "remove", "--force", meta.path);
        try {
            Files.deleteIfExists(registryDir().resolve(meta.id + ".properties"));
        } catch (IOException e) {
            throw new IllegalArgumentException("worktree 已移除，但登记没删掉：" + e.getMessage());
        }
    }

    private boolean apply(Path base, String diff) {
        ProcessBuilder builder = new ProcessBuilder("git", "apply", "--binary", "--whitespace=nowarn");
        builder.directory(base.toFile());
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            process.getOutputStream().write(diff.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
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
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            process.getOutputStream().close();
            byte[] output = process.getInputStream().readAllBytes();
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new Exec(-1, "git 超时");
            }
            return new Exec(process.exitValue(), new String(output, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new Exec(-1, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Exec(-1, "git 被中断");
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
