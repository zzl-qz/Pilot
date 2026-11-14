package com.pilot.core.util;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** 先删除子项，再删除根路径；符号链接仅删除链接本身。 */
public final class FileTrees {
    private FileTrees() {
    }

    public static void delete(Path root) throws IOException {
        delete(root, false);
    }

    public static void deleteIfExists(Path root) throws IOException {
        delete(root, true);
    }

    private static void delete(Path root, boolean allowMissingRoot) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
                if (error instanceof NoSuchFileException && (allowMissingRoot || !file.equals(root))) {
                    return FileVisitResult.CONTINUE;
                }
                throw error;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) {
                    throw error;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
