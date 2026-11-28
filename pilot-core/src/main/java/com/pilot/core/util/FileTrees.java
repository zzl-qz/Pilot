package com.pilot.core.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.BiConsumer;

/** 先删除子项，再删除根路径；符号链接仅删除链接本身。 */
public final class FileTrees {
    public record Deletion(int files, int directories) {
    }

    private FileTrees() {
    }

    /** 原子写文件（UTF-8）：父目录不存在时自动创建，写同目录临时文件后再尽量原子替换目标。 */
    public static void writeAtomic(Path target, String content) throws IOException {
        Objects.requireNonNull(content, "content");
        writeAtomic(target, content.getBytes(StandardCharsets.UTF_8));
    }

    /** 原子写文件：父目录不存在时自动创建，移动失败时清理临时文件。 */
    public static void writeAtomic(Path target, byte[] bytes) throws IOException {
        writeAtomicInternal(target, bytes, false);
    }

    /** 原子写文件并在替换前 {@code fsync} 临时文件，供需要崩溃一致性的状态落盘使用。 */
    public static void writeAtomicSync(Path target, byte[] bytes) throws IOException {
        writeAtomicInternal(target, bytes, true);
    }

    private static void writeAtomicInternal(Path target, byte[] bytes, boolean sync) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(bytes, "bytes");
        Path absolute = target.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        } else {
            parent = Path.of(".").toAbsolutePath();
        }
        Path tmp = Files.createTempFile(parent, ".pilot-", ".tmp");
        try {
            if (sync) {
                try (FileChannel channel =
                    FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    channel.force(true);
                }
            } else {
                Files.write(tmp, bytes);
            }
            try {
                Files.move(tmp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public static void delete(Path root) throws IOException {
        Files.walkFileTree(root, new DeleteVisitor(root, false, null, Files::deleteIfExists));
    }

    public static void deleteIfExists(Path root) throws IOException {
        Files.walkFileTree(root, new DeleteVisitor(root, true, null, Files::deleteIfExists));
    }

    public static void deleteBestEffort(Path root) {
        walkBestEffort(root, (path, error) -> {
        }, Files::deleteIfExists);
    }

    /** 按附件清理原有的逆序统计实际删除的条目。符号链接按删除时的目标类型计数。 */
    public static Deletion deleteBestEffort(Path root, BiConsumer<Path, IOException> onFailure) {
        Objects.requireNonNull(onFailure);
        var entries = new ArrayList<Path>();
        walkBestEffort(root, onFailure, entries::add);
        entries.sort(Comparator.reverseOrder());
        int files = 0;
        int directories = 0;
        for (Path entry : entries) {
            try {
                boolean directory = Files.isDirectory(entry);
                if (Files.deleteIfExists(entry)) {
                    if (directory) {
                        directories++;
                    } else {
                        files++;
                    }
                }
            } catch (IOException error) {
                onFailure.accept(entry, error);
            }
        }
        return new Deletion(files, directories);
    }

    private static void walkBestEffort(Path root, BiConsumer<Path, IOException> onFailure, PathAction action) {
        try {
            Files.walkFileTree(root, new DeleteVisitor(root, true, onFailure, action));
        } catch (IOException error) {
            onFailure.accept(root, error);
        }
    }

    @FunctionalInterface
    private interface PathAction {
        void accept(Path path) throws IOException;
    }

    private static final class DeleteVisitor extends SimpleFileVisitor<Path> {
        private final Path root;
        private final boolean allowMissingRoot;
        private final BiConsumer<Path, IOException> onFailure;
        private final PathAction action;

        private DeleteVisitor(Path root, boolean allowMissingRoot, BiConsumer<Path, IOException> onFailure,
            PathAction action) {
            this.root = root;
            this.allowMissingRoot = allowMissingRoot;
            this.onFailure = onFailure;
            this.action = action;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            return process(file);
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
            if (error instanceof NoSuchFileException && (allowMissingRoot || !file.equals(root))) {
                return FileVisitResult.CONTINUE;
            }
            return handleFailure(file, error);
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
            return error == null ? process(dir) : handleFailure(dir, error);
        }

        private FileVisitResult process(Path path) throws IOException {
            try {
                makeWritable(path);
                action.accept(path);
                return FileVisitResult.CONTINUE;
            } catch (IOException error) {
                return handleFailure(path, error);
            }
        }

        private static void makeWritable(Path path) {
            if (Files.isSymbolicLink(path)) {
                return;
            }
            try {
                DosFileAttributeView dos =
                    Files.getFileAttributeView(path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (dos != null) {
                    dos.setReadOnly(false);
                } else if (HostOs.isWindows()) {
                    path.toFile().setWritable(true);
                }
            } catch (IOException | UnsupportedOperationException ignored) {
                if (HostOs.isWindows()) {
                    path.toFile().setWritable(true);
                }
            }
        }

        private FileVisitResult handleFailure(Path path, IOException error) throws IOException {
            if (onFailure == null) {
                throw error;
            }
            onFailure.accept(path, error);
            return FileVisitResult.CONTINUE;
        }
    }
}
