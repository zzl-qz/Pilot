package com.pilot.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependencyCacheTest {
    @TempDir
    Path root;
    @TempDir
    Path cacheRoot;

    @Test
    void segregatesProjectsImagesAndDependencyChangesAndReleasesLocks() throws Exception {
        Path manifest = root.resolve("package-lock.json");
        Files.writeString(manifest, "one");
        Path first;
        try (var cache = DependencyCache.acquire(cacheRoot, root, "node:22", root)) {
            first = cache.path();
            Files.writeString(first.resolve("dependency"), "cached");
            assertThrows(IOException.class, () -> DependencyCache.acquire(cacheRoot, root, "node:22", root));
        }
        try (var cache = DependencyCache.acquire(cacheRoot, root, "node:22", root)) {
            assertEquals(first, cache.path());
            assertEquals("cached", Files.readString(cache.path().resolve("dependency")));
        }
        try (var cache = DependencyCache.acquire(cacheRoot, root, "node:24", root)) {
            assertNotEquals(first, cache.path());
        }
        Files.writeString(manifest, "two");
        try (var cache = DependencyCache.acquire(cacheRoot, root, "node:22", root)) {
            assertNotEquals(first, cache.path());
        }
        Path other = Files.createDirectory(root.resolve("other"));
        Files.writeString(other.resolve("package-lock.json"), "one");
        try (var cache = DependencyCache.acquire(cacheRoot, other, "node:22", other)) {
            assertNotEquals(first, cache.path());
        }
    }

    @Test
    void refusesOverlappingMountsAndLinkedCacheEntrypoints() throws Exception {
        assertThrows(IOException.class, () -> DependencyCache.validateMount(root, root));
        Path first;
        try (var cache = DependencyCache.acquire(cacheRoot, root, "img", root)) {
            first = cache.path();
        }
        Files.delete(first);
        try {
            Files.createSymbolicLink(first, root);
        } catch (UnsupportedOperationException | FileSystemException e) {
            Assumptions.abort("没有创建链接权限");
        }
        assertThrows(IOException.class, () -> DependencyCache.acquire(cacheRoot, root, "img", root));
        assertThrows(IOException.class, () -> DependencyCache.validateMount(first, cacheRoot));
    }
}
