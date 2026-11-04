package com.pilot.server.frontend;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.CodeSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * 开发期启动检查：在启动日志里询问要不要重新拉取前端依赖（{@code pilot-web/frontend}）。
 *
 * <p>为什么要在 Java 里再问一遍：IDE 里直接运行 {@code PilotServerApplication} 走的是
 * IDEA 自己的启动器，不经过 Maven 生命周期，{@code pilot-server/pom.xml} 挂在
 * {@code validate} 阶段的那次询问根本不会被执行——而开发阶段每天重启十几次的恰恰是这种方式，
 * 每次都要手动去 {@code npm install} 才知道依赖该更新了。
 *
 * <p>只在开发时提问：必须是从 {@code classes} 目录启动（IDE / {@code mvn spring-boot:run}），
 * 并且能在工作目录向上找到源码仓库（{@code pom.xml} + {@code pilot-web/frontend/package.json}）。
 * 打包之后跑 jar 的用户不改前端代码，也不需要这份依赖，所以静默跳过，不打印任何东西。
 *
 * <p>交互形态与 {@code tools/ask-frontend-deps.mjs} 一致（编号菜单、默认「否」），
 * 但改成「输入 + 回车」：IDEA 的控制台不是 TTY，做不到按键即生效。
 * 不是 TTY 时最多等 {@link #timeoutSeconds()} 秒，超时按「否」继续启动——
 * 这是个启动检查，绝不能把启动卡死。
 *
 * <p>去重：两边都会写 {@code .pilot/frontend-deps-ask.stamp}，
 * {@link #STAMP_FRESHNESS} 内已经问过就不再问。{@code mvn spring-boot:run} 会先走
 * {@code validate}（Maven 那版询问）再拉起 JVM，没有这个标记同一次启动会被问两遍。
 */
public final class FrontendDepsPrompt {
    /** 与 tools/ask-frontend-deps.mjs 共用的去重标记，落在 .pilot（不进版本库）。 */
    static final String STAMP_NAME = "frontend-deps-ask.stamp";
    /** 标记的新鲜期：覆盖「Maven 刚问过、JVM 紧接着起来」这一种场景即可。 */
    static final Duration STAMP_FRESHNESS = Duration.ofMinutes(1);
    /** 非 TTY 下的默认等待时长（秒）。 */
    static final int DEFAULT_TIMEOUT_SECONDS = 20;

    static final int NO = 1;
    static final int YES = 2;

    private FrontendDepsPrompt() {
    }

    /** 启动入口：任何异常都不允许影响启动。 */
    public static void askIfDevelopment() {
        try {
            ask();
        } catch (RuntimeException e) {
            System.out.println("  Pilot 启动检查：前端依赖询问失败（" + e.getMessage() + "），继续启动");
        }
    }

    private static void ask() {
        if (!runningFromClasses()) {
            return;
        }
        Path root = repositoryRoot();
        if (root == null) {
            return;
        }
        Path frontend = root.resolve("pilot-web").resolve("frontend");
        if (!Files.isRegularFile(frontend.resolve("package.json")) || skipRequested()) {
            return;
        }
        if (askedRecently(root)) {
            System.out.println("  Pilot 启动检查：刚刚已确认过前端依赖，跳过询问" + "（删掉 .pilot/" + STAMP_NAME + " 可强制再问）");
            return;
        }
        // 依赖缺失没得选：不装的话后面的前端构建必然失败。
        if (!Files.isDirectory(frontend.resolve("node_modules"))) {
            System.out.println("\n  Pilot 启动检查：前端依赖缺失，先安装（pilot-web/frontend）\n");
            install(root, frontend);
            stamp(root);
            return;
        }
        boolean interactive = System.console() != null;
        printMenu(interactive);
        int choice = readChoice(System.in, interactive, timeoutSeconds());
        System.out.println();
        stamp(root);
        if (choice == YES) {
            install(root, frontend);
        } else {
            System.out.println("  跳过依赖更新，继续启动\n");
        }
    }

    private static void printMenu(boolean interactive) {
        System.out.println();
        System.out.println("  Pilot 启动检查");
        System.out.println("  前端依赖：pilot-web/frontend");
        System.out.println();
        System.out.println("  [1] 否，直接启动");
        System.out.println("  [2] 是，拉取最新依赖（npm install）");
        System.out.println();
        System.out
            .println(interactive ? "  输入 1 / 2 后回车，直接回车默认「否」" : "  输入 2 后回车即更新，" + timeoutSeconds() + " 秒内无输入则直接启动");
        System.out.print("  > ");
        System.out.flush();
    }

    /**
     * 读一行选择。轮询 {@code available()} 而不是阻塞 {@code readLine()}：
     * 超时后进程要能继续启动，不能留下一个永远等输入的后台线程。
     */
    static int readChoice(InputStream input, boolean interactive, int timeout) {
        long timeoutNanos = interactive ? -1L : Duration.ofSeconds(Math.max(0, timeout)).toNanos();
        long start = System.nanoTime();
        // 按字节收、最后一次性按 UTF-8 解码：逐字节强转 char 会把 BOM / 多字节字符读成乱码。
        ByteArrayOutputStream typed = new ByteArrayOutputStream();
        while (true) {
            if (timeoutNanos >= 0 && System.nanoTime() - start >= timeoutNanos) {
                break;
            }
            try {
                if (input.available() > 0) {
                    int ch = input.read();
                    if (ch < 0 || ch == '\n' || ch == '\r') {
                        break;
                    }
                    typed.write(ch);
                    if (typed.size() > 16) {
                        break;
                    }
                } else {
                    Thread.sleep(50);
                }
            } catch (IOException e) {
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return parseChoice(typed.toString(StandardCharsets.UTF_8));
    }

    static int parseChoice(String text) {
        // 顺手吃掉 BOM：管道重定向进来的输入偶尔带它，别让一个字节把「2」判成「否」
        String value = text == null ? "" : text.replace("\uFEFF", "").trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "2", "y", "yes" -> YES;
            default -> NO;
        };
    }

    private static boolean install(Path root, Path frontend) {
        List<String> command =
            isWindows() ? List.of("cmd.exe", "/d", "/c", "npm install") : List.of("/bin/sh", "-c", "npm install");
        ProcessBuilder builder = new ProcessBuilder(command).directory(frontend.toFile()).inheritIO();
        Path npmHome = npmHome(root);
        if (npmHome != null) {
            String path = builder.environment().getOrDefault("PATH", "");
            builder.environment().put("PATH", npmHome + File.pathSeparator + path);
        }
        System.out.println("  正在更新前端依赖…\n");
        try {
            int code = builder.start().waitFor();
            if (code == 0) {
                System.out.println("\n  依赖已更新\n");
                return true;
            }
            System.out.println("\n  依赖更新失败（退出码 " + code + "），继续用现有依赖启动\n");
        } catch (IOException e) {
            System.out.println("\n  依赖更新失败（" + e.getMessage() + "），继续用现有依赖启动\n");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    /** 优先用 PATH 上的 npm（Maven 的 frontend-build 用的就是它），没有才退回项目内置 Node。 */
    static Path npmHome(Path root) {
        String name = isWindows() ? "npm.cmd" : "npm";
        String path = System.getenv().getOrDefault("PATH", "");
        for (String entry : path.split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                Path candidate = Path.of(entry).resolve(name);
                if (Files.isRegularFile(candidate)) {
                    return candidate.getParent();
                }
            } catch (InvalidPathException ignored) {
                // PATH 里混进非法路径不稀奇，跳过即可
            }
        }
        Path bundled = root.resolve(".pilot").resolve("runtime").resolve("node").resolve(name);
        return Files.isRegularFile(bundled) ? bundled.getParent() : null;
    }

    /** 从 jar 里启动（打包分发）时不提问：classes 目录才是开发态。 */
    static boolean runningFromClasses() {
        try {
            CodeSource source = FrontendDepsPrompt.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return false;
            }
            return Files.isDirectory(Path.of(source.getLocation().toURI()));
        } catch (URISyntaxException | RuntimeException e) {
            return false;
        }
    }

    /** 向上找源码根目录（与 BrowserRuntime / BundledRuntime 的判定方式一致）。 */
    static Path repositoryRoot() {
        Path dir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("pom.xml"))
                && Files.isRegularFile(dir.resolve("pilot-web").resolve("frontend").resolve("package.json"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }

    static boolean skipRequested() {
        if ("true".equalsIgnoreCase(System.getenv("CI"))) {
            return true;
        }
        if ("1".equals(System.getenv("PILOT_SKIP_DEPS_ASK"))) {
            return true;
        }
        if ("false".equalsIgnoreCase(System.getenv("PILOT_FRONTEND_DEPS_PROMPT"))) {
            return true;
        }
        return "false".equalsIgnoreCase(System.getProperty("pilot.frontend.deps-prompt", "true"));
    }

    static int timeoutSeconds() {
        String value = System.getProperty("pilot.frontend.deps-timeout-seconds");
        if (value == null || value.isBlank()) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
    }

    private static Path stampFile(Path root) {
        return root.resolve(".pilot").resolve(STAMP_NAME);
    }

    private static boolean askedRecently(Path root) {
        Path file = stampFile(root);
        try {
            if (!Files.isRegularFile(file)) {
                return false;
            }
            Instant modified = Files.getLastModifiedTime(file).toInstant();
            Duration age = Duration.between(modified, Instant.now());
            return !age.isNegative() && age.compareTo(STAMP_FRESHNESS) < 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static void stamp(Path root) {
        Path file = stampFile(root);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Instant.now().toString(), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            // 只是去重标记，写不下去不影响启动
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
