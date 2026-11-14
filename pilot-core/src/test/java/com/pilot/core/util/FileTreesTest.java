package com.pilot.core.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

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
        FileTrees.delete(root);
        FileTrees.deleteIfExists(broken);
        assertTrue(Files.isRegularFile(target));
        assertFalse(Files.exists(broken, LinkOption.NOFOLLOW_LINKS));
    }
}
