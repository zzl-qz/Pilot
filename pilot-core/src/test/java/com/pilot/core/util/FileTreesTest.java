package com.pilot.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileTreesTest {
    @TempDir
    Path directory;

    @Test
    void deletesChildrenBeforeParentsAndSupportsRetryAfterSuccess() throws IOException {
        Path root = directory.resolve("构建 缓存");
        Path nested = Files.createDirectories(root.resolve("a/b/c"));
        Files.createFile(nested.resolve("data.txt"));
        Files.createDirectory(root.resolve("empty"));
        FileTrees.delete(root);
        assertFalse(Files.exists(root));
        FileTrees.deleteIfExists(root);
        assertEquals(new FileTrees.Deletion(0, 0), FileTrees.deleteBestEffort(root, (path, error) -> {
            throw new AssertionError(path.toString(), error);
        }));
        assertThrows(NoSuchFileException.class, () -> FileTrees.delete(root));
    }

    @Test
    void removesLinksWithoutTraversingTheirTargets() throws IOException {
        Path root = Files.createDirectory(directory.resolve("cache"));
        Path target = Files.createFile(directory.resolve("keep.txt"));
        Path broken = directory.resolve("broken");
        try {
            Files.createSymbolicLink(root.resolve("outside"), directory);
            Files.createSymbolicLink(broken, directory.resolve("missing"));
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("此系统或当前用户不能创建符号链接");
        }
        assertEquals(new FileTrees.Deletion(0, 2), FileTrees.deleteBestEffort(root, (path, error) -> {
            throw new AssertionError(path.toString(), error);
        }));
        FileTrees.deleteIfExists(broken);
        assertTrue(Files.isRegularFile(target));
        assertFalse(Files.exists(broken, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void reportsPartialCleanupAndContinuesWithOtherEntries() throws IOException {
        Path root = Files.createDirectory(directory.resolve("cache"));
        Path blocked = Files.createDirectory(root.resolve("blocked"));
        Assumptions.assumeTrue(Files.getFileAttributeView(blocked, PosixFileAttributeView.class) != null);
        Path locked = Files.createFile(blocked.resolve("locked"));
        Path sibling = Files.createFile(root.resolve("sibling"));
        var permissions = Files.getPosixFilePermissions(blocked);
        var failures = new ArrayList<Path>();
        try {
            Files.setPosixFilePermissions(blocked, PosixFilePermissions.fromString("r-x------"));
            Assumptions.assumeFalse(Files.isWritable(blocked));
            assertThrows(IOException.class, () -> FileTrees.delete(root));
            if (Files.notExists(sibling)) {
                Files.createFile(sibling);
            }
            assertEquals(new FileTrees.Deletion(1, 0),
                FileTrees.deleteBestEffort(root, (path, error) -> failures.add(path)));
            assertTrue(failures.containsAll(List.of(locked, blocked, root)));
            assertTrue(Files.exists(locked));
            assertFalse(Files.exists(sibling));
        } finally {
            Files.setPosixFilePermissions(blocked, permissions);
        }
    }
}
