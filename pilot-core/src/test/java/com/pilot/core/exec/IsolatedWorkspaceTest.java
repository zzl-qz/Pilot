package com.pilot.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.multi.AgentScope;
import com.pilot.core.workspace.PathValidator;

class IsolatedWorkspaceTest {
    @TempDir
    Path root;

    @Test
    void overlaysNewAndExistingFilesWithoutTouchingTheSourceAndCleansUp() throws Exception {
        Files.writeString(root.resolve("a.txt"), "old");
        Path temporary;
        try (var copy =
            IsolatedWorkspace.create(new PathValidator(root), Map.of("a.txt", "new", "nested/b.txt", "added"))) {
            temporary = copy.root();
            assertEquals("new", Files.readString(temporary.resolve("a.txt")));
            assertEquals("added", Files.readString(temporary.resolve("nested/b.txt")));
            Files.writeString(temporary.resolve("build.out"), "generated");
            copy.verifySourceUnchanged();
        }
        assertFalse(Files.exists(temporary));
        assertEquals("old", Files.readString(root.resolve("a.txt")));
        assertFalse(Files.exists(root.resolve("nested")));
    }

    @Test
    void scopeAndInternalDirectoriesAreNotExposed() throws Exception {
        for (String dir : List.of("src", "private", ".git", ".pilot")) {
            Files.createDirectory(root.resolve(dir));
            Files.writeString(root.resolve(dir + "/secret"), dir);
        }
        var scope = AgentScope.readWrite(List.of(), List.of("private/**"));
        try (var copy = IsolatedWorkspace.create(new PathValidator(root, scope), Map.of())) {
            assertTrue(Files.exists(copy.root().resolve("src/secret")));
            for (String dir : List.of("private", ".git", ".pilot")) {
                assertFalse(Files.exists(copy.root().resolve(dir)));
            }
        }
        assertThrows(RuntimeException.class,
            () -> IsolatedWorkspace.create(new PathValidator(root, scope), Map.of("private/secret", "bad")));
        assertThrows(IOException.class,
            () -> IsolatedWorkspace.create(new PathValidator(root), Map.of(".git/config", "bad")));
        assertThrows(RuntimeException.class,
            () -> IsolatedWorkspace.create(new PathValidator(root), Map.of("../escape", "bad")));
    }

    @Test
    void refusesLinksInsteadOfSilentlyChangingTheProject() throws Exception {
        Files.writeString(root.resolve("a"), "text");
        try {
            Files.createSymbolicLink(root.resolve("link"), root.resolve("a"));
        } catch (UnsupportedOperationException | FileSystemException e) {
            Assumptions.abort("当前平台没有创建符号链接的权限");
        }
        assertThrows(IOException.class, () -> IsolatedWorkspace.create(new PathValidator(root), Map.of()));
    }

    @Test
    void rejectsOversizedFilesAndChangedOrDeletedCandidate() throws Exception {
        Path source = root.resolve("a");
        Files.writeString(source, "old");
        try (var copy = IsolatedWorkspace.create(new PathValidator(root), Map.of())) {
            Files.writeString(copy.root().resolve("a"), "different");
            assertThrows(IOException.class, copy::verifySourceUnchanged);
            Files.delete(copy.root().resolve("a"));
            assertThrows(IOException.class, copy::verifySourceUnchanged);
        }
        try (var channel = FileChannel.open(source, StandardOpenOption.WRITE)) {
            channel.position(32L * 1024 * 1024);
            channel.write(ByteBuffer.wrap(new byte[] {0}));
        }
        assertThrows(IOException.class, () -> IsolatedWorkspace.create(new PathValidator(root), Map.of()));
    }

    @Test
    void candidateCannotBeReplacedByAnInternalSymlinkWithIdenticalContent() throws Exception {
        Files.writeString(root.resolve("a"), "same");
        try (var copy = IsolatedWorkspace.create(new PathValidator(root), Map.of())) {
            Files.writeString(copy.root().resolve("generated"), "same");
            Files.delete(copy.root().resolve("a"));
            try {
                Files.createSymbolicLink(copy.root().resolve("a"), copy.root().resolve("generated"));
            } catch (UnsupportedOperationException | FileSystemException e) {
                Assumptions.abort("当前平台没有创建符号链接的权限");
            }
            assertThrows(IOException.class, copy::verifySourceUnchanged);
        }
    }

    @Test
    void buildCopyExcludesOnlyKnownGeneratedDirectoriesAndRejectsProposalsInsideThem() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "pom");
        for (String directory : List.of("target", "node_modules", "build", "dist")) {
            Files.createDirectory(root.resolve(directory));
            Files.writeString(root.resolve(directory + "/a"), "content");
        }
        try (var copy = IsolatedWorkspace.createForBuild(new PathValidator(root), Map.of())) {
            assertFalse(Files.exists(copy.root().resolve("target")));
            assertFalse(Files.exists(copy.root().resolve("node_modules")));
            assertTrue(Files.exists(copy.root().resolve("build/a")), "没有 Gradle 配置时 build 可能是源码");
            assertTrue(Files.exists(copy.root().resolve("dist/a")), "不能猜测自定义输出目录");
            assertEquals(Set.of("target", "node_modules"), new HashSet<>(copy.excludedDirectories()));
        }
        assertThrows(IOException.class,
            () -> IsolatedWorkspace.createForBuild(new PathValidator(root), Map.of("target/a", "proposal")));
    }

}
