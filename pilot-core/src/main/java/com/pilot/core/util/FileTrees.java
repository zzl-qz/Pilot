package com.pilot.core.util;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.function.BiConsumer;

/** 先删除子项，再删除根路径；符号链接仅删除链接本身。 */
public final class FileTrees {
    public record Deletion(int files, int directories) {
    }

    private FileTrees() {
    }

    public static void delete(Path root) throws IOException {
        Files.walkFileTree(root, new DeleteVisitor(root, false, null));
    }

    public static void deleteIfExists(Path root) throws IOException {
        Files.walkFileTree(root, new DeleteVisitor(root, true, null));
    }

    public static void deleteBestEffort(Path root) {
        deleteBestEffort(root, (path, error) -> {
        });
    }

    /** 逐项报告清理失败并继续遍历，统计实际删除的条目。指向目录的链接计入目录数。 */
    public static Deletion deleteBestEffort(Path root, BiConsumer<Path, IOException> onFailure) {
        var visitor = new DeleteVisitor(root, true, Objects.requireNonNull(onFailure));
        try {
            Files.walkFileTree(root, visitor);
        } catch (IOException error) {
            onFailure.accept(root, error);
        }
        return new Deletion(visitor.files, visitor.directories);
    }

    private static final class DeleteVisitor extends SimpleFileVisitor<Path> {
        private final Path root;
        private final boolean allowMissingRoot;
        private final BiConsumer<Path, IOException> onFailure;
        private int files;
        private int directories;

        private DeleteVisitor(Path root, boolean allowMissingRoot, BiConsumer<Path, IOException> onFailure) {
            this.root = root;
            this.allowMissingRoot = allowMissingRoot;
            this.onFailure = onFailure;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            return remove(file, onFailure != null && attrs.isSymbolicLink() && Files.isDirectory(file));
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
            return error == null ? remove(dir, true) : handleFailure(dir, error);
        }

        private FileVisitResult remove(Path path, boolean directory) throws IOException {
            try {
                if (Files.deleteIfExists(path)) {
                    if (directory) {
                        directories++;
                    } else {
                        files++;
                    }
                }
                return FileVisitResult.CONTINUE;
            } catch (IOException error) {
                return handleFailure(path, error);
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
