package com.pilot.server.multi;

import com.pilot.core.multi.SandboxChange;
import com.pilot.server.config.MultiAgentProperties;
import com.pilot.server.config.PilotDataDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 用 {@code git worktree} 建一个「基线 + 全部沙箱改动」的隔离副本，在里面跑编译与测试。
 *
 * <p>这样做的原因：语义冲突（接口改了、调用方没改）文本上看不出来，只有编译器能判定。
 * 校验永远不碰真实工作区；副本跑完即删。
 *
 * <p>环境不满足（不是 git 仓库、没有可用命令、worktree 创建失败）时返回 {@code skipped}，
 * 由上层把「未校验」如实告诉用户，而不是伪装成通过。
 */
@Component
public class CommandVerificationGate implements VerificationGate {
    private static final Logger log = LoggerFactory.getLogger(CommandVerificationGate.class);
    private static final int MAX_OUTPUT_CHARS = 60_000;
    private static final long GIT_TIMEOUT_SECONDS = 60;

    private final MultiAgentProperties properties;
    private final PilotDataDir dataDir;

    public CommandVerificationGate(MultiAgentProperties properties, PilotDataDir dataDir) {
        this.properties = properties;
        this.dataDir = dataDir;
    }

    @Override
    public Report verify(Path workspace, String baseCommit, List<SandboxChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return Report.skipped("没有待校验的改动");
        }
        Path root = workspace.toAbsolutePath().normalize();
        if (!Files.isDirectory(root.resolve(".git"))) {
            return Report.skipped("工作区不是 git 仓库，无法创建隔离校验副本");
        }
        List<String> commands = commands(root);
        if (commands.isEmpty()) {
            return Report.skipped("没有识别到可用的构建命令，未做硬校验");
        }

        Path copy = null;
        try {
            Path parent = dataDir.file("multi");
            Files.createDirectories(parent);
            copy = Files.createTempDirectory(parent, "verify-");
            Files.deleteIfExists(copy);
            String base = baseCommit == null || baseCommit.isBlank() ? "HEAD" : baseCommit.strip();
            Exec added = exec(root, List.of("git", "worktree", "add", "--detach", copy.toString(), base),
                    GIT_TIMEOUT_SECONDS);
            if (added.exit != 0) {
                return Report.skipped("创建隔离副本失败：" + tail(added.output));
            }
            String warning = applyWorkingTreeChanges(root, copy);
            overlay(copy, changes);

            List<String> checks = new ArrayList<>();
            for (String command : commands) {
                Exec result = exec(copy, shell(command), properties.verifyTimeoutSeconds());
                checks.add(command + " → exit " + result.exit);
                if (result.exit != 0) {
                    return Report.failed(checks,
                            "硬校验失败：" + command + "\n" + tail(result.output));
                }
            }
            return warning.isEmpty()
                    ? Report.passed(checks)
                    : new Report(Report.PASSED, checks, "编译与测试通过（" + warning + "）");
        } catch (IOException | RuntimeException e) {
            log.warn("硬校验执行异常", e);
            return Report.skipped("硬校验执行异常：" + e.getMessage());
        } finally {
            cleanup(root, copy);
        }
    }

    /** 配置优先，否则按项目文件推断；只跑「编译 + 测试」这类无副作用命令。 */
    private List<String> commands(Path root) {
        if (!properties.verifyCommands().isEmpty()) {
            return properties.verifyCommands();
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            String wrapper = Files.isRegularFile(root.resolve(windows ? "mvnw.cmd" : "mvnw"))
                    ? (windows ? "mvnw.cmd" : "./mvnw")
                    : "mvn";
            // 先编译再测试：编译不通过时测试没有意义，失败即停
            return List.of(wrapper + " -q -DskipTests compile", wrapper + " -q test");
        }
        if (Files.isRegularFile(root.resolve("build.gradle")) || Files.isRegularFile(root.resolve("build.gradle.kts"))) {
            String wrapper = Files.isRegularFile(root.resolve(windows ? "gradlew.bat" : "gradlew"))
                    ? (windows ? "gradlew.bat" : "./gradlew")
                    : "gradle";
            return List.of(wrapper + " compileJava -q", wrapper + " test -q");
        }
        if (Files.isRegularFile(root.resolve("package.json"))) {
            return List.of("npm run build --silent");
        }
        return List.of();
    }

    /** 把用户工作区里未提交的改动也铺到副本上：子 Agent 读的是这份内容，diff 才对得上。 */
    private String applyWorkingTreeChanges(Path root, Path copy) throws IOException {
        Exec diff = exec(root, List.of("git", "--no-pager", "diff", "HEAD", "--", "."), GIT_TIMEOUT_SECONDS);
        if (diff.exit != 0 || diff.output.isBlank()) {
            return "";
        }
        Path patch = copy.getParent().resolve(copy.getFileName() + ".patch");
        try {
            Files.writeString(patch, diff.output, StandardCharsets.UTF_8);
            Exec applied = exec(copy, List.of("git", "apply", "--whitespace=nowarn", patch.toString()),
                    GIT_TIMEOUT_SECONDS);
            return applied.exit == 0 ? "" : "工作区未提交改动未能套用，校验结果仅供参考";
        } finally {
            Files.deleteIfExists(patch);
        }
    }

    private void overlay(Path copy, List<SandboxChange> changes) throws IOException {
        for (SandboxChange change : changes) {
            if (change.status() != com.pilot.core.multi.ChangeStatus.PENDING
                    && change.status() != com.pilot.core.multi.ChangeStatus.ACCEPTED) {
                continue;
            }
            Path target = copy.resolve(change.path()).normalize();
            if (!target.startsWith(copy)) {
                throw new IOException("改动路径越界: " + change.path());
            }
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, change.newContent(), StandardCharsets.UTF_8);
        }
    }

    private void cleanup(Path root, Path copy) {
        if (copy == null) {
            return;
        }
        try {
            exec(root, List.of("git", "worktree", "remove", "--force", copy.toString()), GIT_TIMEOUT_SECONDS);
        } catch (RuntimeException e) {
            log.debug("移除 worktree 失败: {}", copy, e);
        }
        deleteRecursively(copy);
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(file -> {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // 副本目录可能被占用，留给系统清理
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    /** 命令来自配置或项目文件，不受模型控制；这里用 shell 只是为了支持带参数的命令行。 */
    private static List<String> shell(String command) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return windows
                ? List.of("cmd.exe", "/c", command)
                : List.of("/bin/sh", "-c", command);
    }

    private record Exec(int exit, String output) {
    }

    private static Exec exec(Path directory, List<String> command, long timeoutSeconds) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        builder.redirectErrorStream(true);
        Process process = null;
        try {
            process = builder.start();
            process.getOutputStream().close();
            Process running = process;
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> read(running.getInputStream()));
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                output.cancel(true);
                return new Exec(-1, "命令超时（" + timeoutSeconds + " 秒）");
            }
            return new Exec(process.exitValue(), output.join());
        } catch (IOException e) {
            return new Exec(-1, "无法执行 " + String.join(" ", command) + "：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return new Exec(-1, "命令被中断");
        }
    }

    private static String read(InputStream stream) {
        try (stream) {
            byte[] bytes = stream.readNBytes(MAX_OUTPUT_CHARS);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String tail(String output) {
        if (output == null) {
            return "";
        }
        String trimmed = output.strip();
        return trimmed.length() <= 4_000 ? trimmed : "…" + trimmed.substring(trimmed.length() - 4_000);
    }
}
