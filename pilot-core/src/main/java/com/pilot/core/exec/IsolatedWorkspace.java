package com.pilot.core.exec;

import com.pilot.core.workspace.PathValidator;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** 物化可见文件与提案；不挂载真实工作区、Git 管理目录或 Pilot 数据目录。 */
public final class IsolatedWorkspace implements AutoCloseable {
    private static final long MAX_FILE = 32L * 1024 * 1024, MAX_TOTAL = 256L * 1024 * 1024;
    private final Path root;
    private final Map<String, byte[]> baseline = new LinkedHashMap<>();

    private IsolatedWorkspace(Path root) { this.root = root; }
    public Path root() { return root; }

    public static IsolatedWorkspace create(PathValidator source, Map<String, String> proposals) throws IOException {
        IsolatedWorkspace copy = new IsolatedWorkspace(Files.createTempDirectory("pilot-isolated-").toRealPath());
        long[] total = {0};
        try {
            Files.walkFileTree(source.root(), new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (dir.equals(source.root())) return FileVisitResult.CONTINUE;
                    if (reserved(source.display(dir)) || !source.allowsDirectory(dir)) return FileVisitResult.SKIP_SUBTREE;
                    Files.createDirectories(copy.root.resolve(source.display(dir)));
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String name = source.display(file);
                    if (reserved(name) || !source.allows(file)) return FileVisitResult.CONTINUE;
                    if (!attrs.isRegularFile() || attrs.isSymbolicLink()) throw new IOException("隔离副本不支持链接或特殊文件：" + name);
                    if (attrs.size() > MAX_FILE || (total[0] += attrs.size()) > MAX_TOTAL || copy.baseline.size() >= 50000)
                        throw new IOException("隔离副本超过文件或总大小上限，命令未执行：" + name);
                    source.resolve(name); // 复制前再检查祖先路径，不能沿已换成外部链接的目录读取。
                    Path target = copy.root.resolve(name);
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
                    if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) > MAX_FILE)
                        throw new IOException("快照期间文件发生变化：" + name);
                    copy.baseline.put(name, hash(target));
                    return FileVisitResult.CONTINUE;
                }
            });
            PathValidator targetPaths = new PathValidator(copy.root, source.scope());
            for (var entry : proposals.entrySet()) {
                if (reserved(entry.getKey())) throw new IOException("提案包含受保护路径：" + entry.getKey());
                Path target = targetPaths.resolve(entry.getKey());
                byte[] content = entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if (content.length > MAX_FILE || (total[0] += content.length) > MAX_TOTAL)
                    throw new IOException("提案超过隔离副本大小上限");
                if (!copy.baseline.containsKey(targetPaths.display(target)) && copy.baseline.size() >= 50000)
                    throw new IOException("提案超过隔离副本文件数量上限");
                Files.createDirectories(target.getParent());
                Files.write(target, content);
                copy.baseline.put(targetPaths.display(target), hash(target));
            }
            return copy;
        } catch (IOException | RuntimeException e) {
            try { copy.close(); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    private static boolean reserved(String path) {
        for (String part : path.replace('\\', '/').split("/"))
            if (part.equalsIgnoreCase(".git") || part.equalsIgnoreCase(".pilot")) return true;
        return false;
    }

    /** 构建输出可以留在临时目录；被校验的源文件若遭命令改写，不能报告原候选通过。 */
    public void verifySourceUnchanged() throws IOException {
        for (var entry : baseline.entrySet()) {
            for (Path part = root.resolve(entry.getKey()); !part.equals(root); part = part.getParent())
                if (Files.isSymbolicLink(part)) throw new IOException("校验命令把候选文件替换成了链接：" + entry.getKey());
            Path file = new PathValidator(root).resolve(entry.getKey());
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_FILE
                    || !Arrays.equals(entry.getValue(), hash(file)))
                throw new IOException("校验命令改写了候选文件，结果无效：" + entry.getKey());
        }
    }

    private static byte[] hash(Path file) throws IOException {
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192]; int count; long bytes = 0;
            while ((count = input.read(buffer)) >= 0) {
                if ((bytes += count) > MAX_FILE) throw new IOException("快照文件超过大小上限");
                digest.update(buffer, 0, count);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    @Override public void close() throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException { if (error != null) throw error; Files.delete(dir); return FileVisitResult.CONTINUE; }
        });
    }
}
