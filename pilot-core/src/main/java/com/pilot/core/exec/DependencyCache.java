package com.pilot.core.exec;

import com.pilot.core.multi.DiffSandbox;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** 项目、镜像和依赖清单共同分区。锁文件不进入容器，构建只能修改自己的缓存。 */
public final class DependencyCache implements AutoCloseable {
    private final Path path;
    private final FileChannel channel;
    private final FileLock lock;
    private DependencyCache(Path path, FileChannel channel, FileLock lock) {
        this.path = path; this.channel = channel; this.lock = lock;
    }
    public Path path() { return path; }

    public static DependencyCache acquire(Path cacheRoot, Path workspace, String image, Path snapshot) throws IOException {
        if (cacheRoot == null) return new DependencyCache(null, null, null);
        Files.createDirectories(cacheRoot);
        Path root = cacheRoot.toRealPath();
        StringBuilder key = new StringBuilder(workspace.toRealPath().toString()).append('\n').append(image);
        // 按稳定相对路径排序，只读快照内的依赖定义；提案修改锁文件时使用独立缓存。
        try (var files = Files.walk(snapshot)) {
            for (Path file : files.filter(Files::isRegularFile).filter(DependencyCache::manifest).sorted().toList()) {
                if (Files.isSymbolicLink(file) || Files.size(file) > 32L * 1024 * 1024) throw new IOException("依赖清单无效");
                key.append('\n').append(snapshot.relativize(file).toString().replace('\\', '/')).append(':')
                        .append(DiffSandbox.fingerprint(Files.readString(file)));
            }
        }
        Path folder = root.resolve(DiffSandbox.fingerprint(key.toString()));
        if (Files.isSymbolicLink(folder)) throw new IOException("依赖缓存目录不能是链接");
        Files.createDirectories(folder);
        Path lockFile = folder.resolve("cache.lock"), content = folder.resolve("content");
        if (Files.isSymbolicLink(lockFile) || Files.isSymbolicLink(content)) throw new IOException("依赖缓存包含链接入口");
        FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock lock = null;
        try {
            try { lock = channel.tryLock(); } catch (OverlappingFileLockException ignored) { }
            if (lock == null) throw new IOException("本项目依赖缓存正在被其他校验使用，请稍后重试");
            Files.createDirectories(content);
            validateMount(content, snapshot.toRealPath());
            return new DependencyCache(content.toRealPath(), channel, lock);
        } catch (IOException | RuntimeException e) {
            if (lock != null) lock.release(); channel.close(); throw e;
        }
    }

    private static boolean manifest(Path path) {
        return Set.of("pom.xml", "package.json", "package-lock.json", "npm-shrinkwrap.json", "pnpm-lock.yaml", "yarn.lock",
                "build.gradle", "build.gradle.kts", "gradle.lockfile", "gradle-wrapper.properties", "maven-wrapper.properties")
                .contains(path.getFileName().toString());
    }
    static void validateMount(Path path, Path workspace) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        // 系统临时目录的 /var -> /private/var 是合法的祖先别名；入口自身不能被换成链接。
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("依赖缓存不是独立目录");
        Path real = normalized.toRealPath(), work = workspace.toRealPath();
        if (real.startsWith(work) || work.startsWith(real)) throw new IOException("依赖缓存不能与工作副本重叠");
    }
    @Override public void close() throws IOException {
        try { if (lock != null) lock.release(); } finally { if (channel != null) channel.close(); }
    }
}
