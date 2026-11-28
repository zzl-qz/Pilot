package com.pilot.core.exec;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.pilot.core.util.FileTrees;
import com.pilot.core.util.HostOs;
import com.pilot.core.util.Sha256;
import com.pilot.core.workspace.RealPaths;

/**
 * 把一条命令放进当前操作系统能提供的文件系统沙箱。
 *
 * <p>macOS 用 Seatbelt，Linux 用 bubblewrap。两者都是：默认拒绝写入，工作区和构建缓存可写，
 * {@code .git/config}、{@code hooks}、{@code info} 只读。网络默认放行，因为 Seatbelt 和
 * bubblewrap 都不能按域名放行 Maven Central；要关掉设 {@code PILOT_EXECUTION_COMMAND_NETWORK=false}。
 *
 * <p>Windows 用独立运行器创建写入受限令牌。未配置运行器时与找不到 sandbox-exec/bwrap 一样在本机执行并标明未隔离；
 * 配置了运行器但缺失或探测失败时命令不启动，不退回无限制进程。
 */
public final class OsSandbox {
    private static final List<String> CACHE_DIRS = List.of(".m2", ".gradle", ".npm", ".cache", ".cargo", ".nuget");

    private OsSandbox() {
    }

    public enum Family {
        MAC, LINUX, WINDOWS;

        static Family current() {
            if (HostOs.isMac()) {
                return MAC;
            }
            if (HostOs.isWindows()) {
                return WINDOWS;
            }
            return LINUX;
        }
    }

    /** @param enforced 这次启动是否真的进了系统沙箱 */
    public record Launch(List<String> command, String label, boolean enforced) {
    }

    public static Launch wrap(List<String> command, Path workspace) {
        return wrap(command, workspace, List.of());
    }

    /** @param extraWritable 同一项目关联的其他目录：与工作区一样可写，{@code .git} 元数据仍只读 */
    public static Launch wrap(List<String> command, Path workspace, List<Path> extraWritable) {
        return wrap(command, workspace, Family.current(), networkAllowed(), extraWritable);
    }

    static Launch wrap(List<String> command, Path workspace, Family family, boolean network) {
        return wrap(command, workspace, family, network, List.of());
    }

    static Launch wrap(List<String> command, Path workspace, Family family, boolean network, List<Path> extraWritable) {
        if (!enabled()) {
            return new Launch(List.copyOf(command), "隔离：未启用（pilot.os.sandbox=false，命令在本机执行）", false);
        }
        Path root = real(workspace);
        List<Path> writable = writableRoots(root, family, extraWritable);
        List<Path> locked = protectedWrites(root, extraWritable);
        return switch (family) {
            case MAC -> mac(command, root, writable, locked, network);
            case LINUX -> linux(command, root, writable, locked, network);
            case WINDOWS -> windows(command, root, writable);
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

    /**
     * Windows 写入限制。没有配置运行器时在本机执行并标明未隔离，和 macOS/Linux 找不到沙箱工具时一致；
     * 配置了运行器但探测失败时返回空命令，调用方不得启动。
     * 运行器参数：{@code --probe} 只建令牌；正式启动再带 {@code --workspace}、{@code --temp}、
     * 若干 {@code --write} 和命令。
     */
    static Launch windows(List<String> command, Path workspace, List<Path> writable) {
        Path runner = windowsRunner();
        if (runner == null) {
            return new Launch(List.copyOf(command), "隔离：未启用（Windows 受限令牌运行器未配置，命令在本机执行，写入不受限）", false);
        }
        if (!Files.isRegularFile(runner)) {
            return refused("隔离：命令未启动（Windows 写入限制运行器缺失，沙箱未能建立）");
        }
        int status;
        try {
            status = probe(runner);
        } catch (IOException e) {
            return refused("隔离：命令未启动（Windows 沙箱未能建立：" + e.getMessage() + "）");
        }
        if (status != 0) {
            return refused("隔离：命令未启动（Windows 受限令牌建立失败，沙箱未能建立，退出码 " + status + "）");
        }
        Path temp = real(Path.of(System.getProperty("java.io.tmpdir", "/tmp")))
            .resolve("pilot-sandbox-" + Integer.toUnsignedString(workspace.toString().hashCode()));
        List<String> argv = new ArrayList<>();
        argv.add(runner.toString());
        argv.add("--workspace");
        argv.add(workspace.toString());
        argv.add("--temp");
        argv.add(temp.toString());
        for (Path root : windowsWriteRoots(writable, workspace)) {
            argv.add("--write");
            argv.add(root.toString());
        }
        argv.add("--");
        argv.addAll(command);
        return new Launch(List.copyOf(argv), "隔离：Windows 受限令牌（写入已限制，工作区、构建缓存与本次私有临时目录可写，读盘和网络仍按系统原样）", true);
    }

    /**
     * 交给运行器做 {@code --write} 的目录。工作区已由 {@code --workspace} 传过去，系统临时目录由
     * {@code --temp} 换成这次私有的子目录，两者都不再传——尤其系统临时目录根必须排除，
     * 否则整个用户临时目录都变成可写，私有临时目录就形同虚设。
     * 运行器只会授权真实存在的路径，未安装的缓存目录不传，避免整条命令因路径不存在而拒跑。
     */
    static List<Path> windowsWriteRoots(List<Path> writable, Path workspace) {
        Path systemTemp = real(Path.of(System.getProperty("java.io.tmpdir", "/tmp")));
        List<Path> roots = new ArrayList<>();
        for (Path root : writable) {
            if (sameDirectory(root, workspace) || sameDirectory(root, systemTemp) || !Files.isDirectory(root)) {
                continue;
            }
            roots.add(root);
        }
        return roots;
    }

    /**
     * 同一个目录可能以短名（8.3）和长名两种形式出现，{@code Path.equals} 比不出来。
     * Windows 上 {@code java.io.tmpdir} 默认就是短名，直接按字符串比会把系统临时目录漏进去。
     */
    private static boolean sameDirectory(Path left, Path right) {
        if (left.equals(right)) {
            return true;
        }
        try {
            return Files.exists(left) && Files.exists(right) && Files.isSameFile(left, right);
        } catch (IOException ignored) {
            return false;
        }
    }

    static Path windowsRunner() {
        String configured = System.getProperty("pilot.windows.sandbox.runner", "");
        if (configured.isBlank()) {
            configured = System.getenv("PILOT_WINDOWS_SANDBOX_RUNNER");
        }
        if (configured == null || configured.isBlank()) {
            return null;
        }
        return Path.of(configured);
    }

    private static int probe(Path runner) throws IOException {
        Process process = new ProcessBuilder(runner.toString(), "--probe").redirectErrorStream(true).start();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return -1;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return -1;
        }
        return process.exitValue();
    }

    private static Launch refused(String label) {
        return new Launch(List.of(), label, false);
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
        Path profileDir = profileDirectory();
        StringBuilder profile = new StringBuilder();
        profile.append("(version 1)\n(deny default)\n");
        profile.append("(allow file-read*)\n(allow file-read-metadata)\n");
        for (Path root : writable) {
            profile.append("(allow file-write* (subpath \"").append(escape(root)).append("\"))\n");
        }

        profile.append("(allow process*)\n(allow signal)\n(allow sysctl-read)\n");
        profile.append("(allow mach*)\n(allow ipc-posix*)\n");
        profile.append("(allow file-ioctl)\n(allow file-map-executable)\n(allow system-socket)\n");
        // git、编译器会用读写方式打开这些设备。不放行时 git status 会报 Operation not permitted。
        for (String device : List.of("/dev/null", "/dev/zero", "/dev/urandom", "/dev/random", "/dev/tty",
            "/dev/dtracehelper")) {
            profile.append("(allow file-read* (literal \"").append(device).append("\"))\n");
            profile.append("(allow file-write* (literal \"").append(device).append("\"))\n");
        }
        for (Path path : locked) {
            profile.append("(deny file-write* (subpath \"").append(escape(path)).append("\"))\n");
        }
        profile.append("(deny file-write* (subpath \"").append(escape(profileDir)).append("\"))\n");
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
            if (Files.exists(path)) {
                argv.add("--ro-bind");
                argv.add(path.toString());
                argv.add(path.toString());
            } else if ("hooks".equals(path.getFileName() == null ? "" : path.getFileName().toString())
                && path.getParent() != null && Files.isDirectory(path.getParent())) {
                // .git/hooks 尚未创建时用只读空 tmpfs 占位，防止沙箱内新建恶意 Git hook。
                argv.add("--tmpfs");
                argv.add(path.toString());
                argv.add("--remount-ro");
                argv.add(path.toString());
            }
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
        return writableRoots(workspace, family, List.of());
    }

    static List<Path> writableRoots(Path workspace, Family family, List<Path> extraWritable) {
        Path realWorkspace = real(workspace);
        Set<Path> roots = new LinkedHashSet<>(RealPaths.visible(realWorkspace, extraWritable, false));
        roots.add(real(Path.of(System.getProperty("java.io.tmpdir", "/tmp"))));
        roots.add(real(Path.of("/tmp")));
        if (family == Family.MAC) {
            roots.add(real(Path.of("/private/tmp")));
            roots.add(real(Path.of("/private/var/folders")));
            roots.add(real(Path.of("/var/folders")));
        }
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
        return protectedWrites(workspace, List.of());
    }

    /** 每个可见根的 git 元数据都只读：多挂一个目录不能连带放开它的 hooks 和配置。 */
    static List<Path> protectedWrites(Path workspace, List<Path> extraWritable) {
        List<Path> visible = RealPaths.visible(real(workspace), extraWritable, false);
        List<Path> locked = new ArrayList<>();
        for (Path path : visible) {
            Path git = path.resolve(".git");
            if (Files.isRegularFile(git)) {
                try {
                    String pointer = Files.readString(git, StandardCharsets.UTF_8).strip();
                    if (pointer.startsWith("gitdir:")) {
                        addGitLocks(locked,
                            git.getParent().resolve(pointer.substring("gitdir:".length()).strip()).normalize());
                        continue;
                    }
                } catch (IOException ignored) {
                    // Fall back to conventional .git paths if worktree metadata cannot be read.
                }
            }
            addGitLocks(locked, git);
        }
        return List.copyOf(locked);
    }

    private static void addGitLocks(List<Path> locked, Path git) {
        locked.add(git.resolve("config"));
        locked.add(git.resolve("hooks"));
        locked.add(git.resolve("info"));
    }

    private static String label(String backend, boolean network) {
        return "隔离：已隔离，" + backend + "（工作区可写，构建缓存可写，.git 配置只读，网络" + (network ? "允许" : "关闭") + "）";
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

    static Path profileDirectory() {
        String home = System.getProperty("user.home", "");
        if (!home.isBlank()) {
            return real(Path.of(home)).resolve(".pilot").resolve("sandbox-profiles");
        }
        String user = System.getProperty("user.name", "pilot");
        return real(Path.of(System.getProperty("java.io.tmpdir")))
            .resolve(".pilot-sandbox-profiles-" + Integer.toUnsignedString(user.hashCode()));
    }

    static Path profileFile(String profile) throws IOException {
        Path dir = profileDirectory();
        Files.createDirectories(dir);
        var privateDirectory = PosixFilePermissions.fromString("rwx------");
        var privateFile = PosixFilePermissions.fromString("rw-------");
        try {
            Files.setPosixFilePermissions(dir, privateDirectory);
        } catch (UnsupportedOperationException ignored) {
            // POSIX permissions are unavailable on non-POSIX filesystems.
        }
        Path file = dir.resolve("pilot-seatbelt-" + profileHash(profile) + ".sb");
        if (!Files.isRegularFile(file) || !profile.equals(Files.readString(file, StandardCharsets.UTF_8))) {
            FileTrees.writeAtomic(file, profile);
            try {
                Files.setPosixFilePermissions(file, privateFile);
            } catch (UnsupportedOperationException ignored) {
                // POSIX permissions are unavailable on non-POSIX filesystems.
            }
        }
        return file;
    }

    private static String profileHash(String profile) {
        return Sha256.hex(profile).substring(0, 16);
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
