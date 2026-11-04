package com.pilot.core.exec;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 把一条命令放进当前操作系统能提供的文件系统沙箱。
 *
 * <p>macOS 用 Seatbelt，Linux 用 bubblewrap。两者都是：默认拒绝写入，工作区和构建缓存可写，
 * {@code .git/config}、{@code hooks}、{@code info} 只读。网络默认放行，因为 Seatbelt 和
 * bubblewrap 都不能按域名放行 Maven Central；要关掉设 {@code PILOT_EXECUTION_COMMAND_NETWORK=false}。
 *
 * <p>Windows 的 MXC 和受限令牌都要原生 API，这个进程创建不了。这时原命令照跑，但结果里标明未隔离。
 */
public final class OsSandbox {
    private static final List<String> CACHE_DIRS = List.of(".m2", ".gradle", ".npm", ".cache", ".cargo", ".nuget");

    private OsSandbox() {
    }

    public enum Family {
        MAC, LINUX, WINDOWS;

        static Family current() {
            String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (name.contains("mac") || name.contains("darwin")) {
                return MAC;
            }
            if (name.contains("win")) {
                return WINDOWS;
            }
            return LINUX;
        }
    }

    /** @param enforced 这次启动是否真的进了系统沙箱 */
    public record Launch(List<String> command, String label, boolean enforced) {
    }

    public static Launch wrap(List<String> command, Path workspace) {
        return wrap(command, workspace, Family.current(), networkAllowed());
    }

    static Launch wrap(List<String> command, Path workspace, Family family, boolean network) {
        if (!enabled()) {
            return new Launch(List.copyOf(command), "隔离：未启用（pilot.os.sandbox=false，命令在本机执行）", false);
        }
        Path root = real(workspace);
        List<Path> writable = writableRoots(root, family);
        List<Path> locked = protectedWrites(root);
        return switch (family) {
            case MAC -> mac(command, root, writable, locked, network);
            case LINUX -> linux(command, root, writable, locked, network);
            case WINDOWS -> new Launch(List.copyOf(command), "隔离：未启用（Windows 需要 MXC 或受限令牌，当前进程无法创建，命令在本机执行）", false);
        };
    }

    static boolean networkAllowed() {
        String property = System.getProperty("pilot.execution.command-network");
        if (property != null && !property.isBlank()) {
            return Boolean.parseBoolean(property.strip());
        }
        String env = System.getenv("PILOT_EXECUTION_COMMAND_NETWORK");
        if (env != null && !env.isBlank()) {
            return Boolean.parseBoolean(env.strip());
        }
        return true;
    }

    private static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("pilot.os.sandbox", "true"));
    }

    private static Launch mac(List<String> command, Path workspace, List<Path> writable, List<Path> locked,
        boolean network) {
        Path sandboxExec = Path.of("/usr/bin/sandbox-exec");
        if (!Files.isExecutable(sandboxExec)) {
            return new Launch(List.copyOf(command), "隔离：未启用（找不到 /usr/bin/sandbox-exec，命令在本机执行）", false);
        }
        String profile = seatbeltProfile(writable, locked, network);
        Path file;
        try {
            file = profileFile(profile);
        } catch (IOException e) {
            return new Launch(List.copyOf(command), "隔离：未启用（Seatbelt 配置写不出去：" + e.getMessage() + "，命令在本机执行）", false);
        }
        List<String> argv = new ArrayList<>();
        argv.add(sandboxExec.toString());
        argv.add("-f");
        argv.add(file.toString());
        argv.add("--");
        argv.addAll(command);
        return new Launch(List.copyOf(argv), label("macOS Seatbelt", network), true);
    }

    private static Launch linux(List<String> command, Path workspace, List<Path> writable, List<Path> locked,
        boolean network) {
        Path bwrap = findBwrap(workspace);
        if (bwrap == null) {
            return new Launch(List.copyOf(command), "隔离：未启用（找不到 bwrap，命令在本机执行。安装 bubblewrap 后才会限制文件系统）", false);
        }
        return new Launch(bwrapCommand(bwrap, writable, locked, network, command), label("Linux bubblewrap", network),
            true);
    }

    static String seatbeltProfile(List<Path> writable, List<Path> locked, boolean network) {
        StringBuilder profile = new StringBuilder();
        profile.append("(version 1)\n(deny default)\n");
        profile.append("(allow file-read*)\n(allow file-read-metadata)\n");
        for (Path root : writable) {
            profile.append("(allow file-write* (subpath \"").append(escape(root)).append("\"))\n");
        }
        // Seatbelt 后写的规则覆盖先写的。保护路径必须放在工作区允许之后。
        for (Path path : locked) {
            profile.append("(deny file-write* (subpath \"").append(escape(path)).append("\"))\n");
        }
        profile.append("(allow process*)\n(allow signal)\n(allow sysctl-read)\n");
        profile.append("(allow mach-lookup)\n(allow ipc-posix*)\n");
        // git、编译器会用读写方式打开这些设备。不放行时 git status 会报 Operation not permitted。
        for (String device : List.of("/dev/null", "/dev/zero", "/dev/urandom", "/dev/random", "/dev/tty",
            "/dev/dtracehelper")) {
            profile.append("(allow file-read* (literal \"").append(device).append("\"))\n");
            profile.append("(allow file-write* (literal \"").append(device).append("\"))\n");
        }
        profile.append(network ? "(allow network*)\n" : "(deny network*)\n");
        return profile.toString();
    }

    static List<String> bwrapCommand(Path bwrap, List<Path> writable, List<Path> locked, boolean network,
        List<String> command) {
        List<String> argv = new ArrayList<>();
        argv.add(bwrap.toString());
        argv.add("--die-with-parent");
        argv.add("--ro-bind");
        argv.add("/");
        argv.add("/");
        for (Path root : writable) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            argv.add("--bind");
            argv.add(root.toString());
            argv.add(root.toString());
        }
        for (Path path : locked) {
            if (!Files.exists(path)) {
                continue;
            }
            argv.add("--ro-bind");
            argv.add(path.toString());
            argv.add(path.toString());
        }
        if (!network) {
            argv.add("--unshare-net");
        }
        for (String device : List.of("/dev/null", "/dev/zero", "/dev/urandom", "/dev/random", "/dev/tty")) {
            if (Files.exists(Path.of(device))) {
                argv.add("--bind");
                argv.add(device);
                argv.add(device);
            }
        }
        argv.add("--");
        argv.addAll(command);
        return argv;
    }

    static List<Path> writableRoots(Path workspace, Family family) {
        Set<Path> roots = new LinkedHashSet<>();
        roots.add(real(workspace));
        roots.add(real(Path.of(System.getProperty("java.io.tmpdir", "/tmp"))));
        Path home = Path.of(System.getProperty("user.home", ""));
        if (!home.toString().isBlank()) {
            for (String cache : CACHE_DIRS) {
                roots.add(real(home.resolve(cache)));
            }
            if (family == Family.MAC) {
                roots.add(real(home.resolve("Library/Caches")));
            }
        }
        return List.copyOf(roots);
    }

    static List<Path> protectedWrites(Path workspace) {
        Path root = real(workspace);
        return List.of(root.resolve(".git/config"), root.resolve(".git/hooks"), root.resolve(".git/info"));
    }

    private static String label(String backend, boolean network) {
        return "隔离：" + backend + "（工作区可写，构建缓存可写，.git 配置只读，网络" + (network ? "允许" : "关闭") + "）";
    }

    private static Path findBwrap(Path workspace) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        Path root = workspace.toAbsolutePath().normalize();
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir).resolve("bwrap");
            if (!Files.isExecutable(candidate)) {
                continue;
            }
            Path resolved = real(candidate);
            if (resolved.startsWith(root)) {
                continue;
            }
            return resolved;
        }
        return null;
    }

    private static Path profileFile(String profile) throws IOException {
        Path dir = real(Path.of(System.getProperty("java.io.tmpdir")));
        Path file = dir.resolve("pilot-seatbelt-" + Integer.toUnsignedString(profile.hashCode()) + ".sb");
        if (!Files.exists(file) || !profile.equals(Files.readString(file))) {
            Files.writeString(file, profile, StandardCharsets.UTF_8);
        }
        return file;
    }

    static Path real(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        try {
            if (Files.exists(absolute)) {
                return absolute.toRealPath();
            }
            Path parent = absolute.getParent();
            if (parent != null && Files.exists(parent)) {
                return parent.toRealPath().resolve(absolute.getFileName());
            }
        } catch (IOException ignored) {
            // 解析不了就用绝对路径，Seatbelt 会按这个路径匹配
        }
        return absolute;
    }

    private static String escape(Path path) {
        return path.toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
