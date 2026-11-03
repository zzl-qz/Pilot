package com.pilot.core.exec;

import com.pilot.core.multi.AgentScope;
import com.pilot.core.workspace.PathValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IsolatedWorkspaceTest {
    @TempDir Path root;

    @Test void overlaysNewAndExistingFilesWithoutTouchingTheSourceAndCleansUp() throws Exception {
        Files.writeString(root.resolve("a.txt"), "old");
        Path temporary;
        try (var copy = IsolatedWorkspace.create(new PathValidator(root), Map.of("a.txt", "new", "nested/b.txt", "added"))) {
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

    @Test void scopeAndInternalDirectoriesAreNotExposed() throws Exception {
        for (String dir : List.of("src", "private", ".git", ".pilot")) {
            Files.createDirectory(root.resolve(dir)); Files.writeString(root.resolve(dir + "/secret"), dir);
        }
        var scope = AgentScope.readWrite(List.of(), List.of("private/**"));
        try (var copy = IsolatedWorkspace.create(new PathValidator(root, scope), Map.of())) {
            assertTrue(Files.exists(copy.root().resolve("src/secret")));
            for (String dir : List.of("private", ".git", ".pilot")) assertFalse(Files.exists(copy.root().resolve(dir)));
        }
        assertThrows(RuntimeException.class, () -> IsolatedWorkspace.create(new PathValidator(root, scope), Map.of("private/secret", "bad")));
        assertThrows(java.io.IOException.class, () -> IsolatedWorkspace.create(new PathValidator(root), Map.of(".git/config", "bad")));
        assertThrows(RuntimeException.class, () -> IsolatedWorkspace.create(new PathValidator(root), Map.of("../escape", "bad")));
    }

    @Test void refusesLinksInsteadOfSilentlyChangingTheProject() throws Exception {
        Files.writeString(root.resolve("a"), "text");
        try { Files.createSymbolicLink(root.resolve("link"), root.resolve("a")); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            org.junit.jupiter.api.Assumptions.abort("当前平台没有创建符号链接的权限");
        }
        assertThrows(java.io.IOException.class, () -> IsolatedWorkspace.create(new PathValidator(root), Map.of()));
    }

    @Test void rejectsOversizedFilesAndChangedOrDeletedCandidate() throws Exception {
        Path source = root.resolve("a"); Files.writeString(source, "old");
        try (var copy = IsolatedWorkspace.create(new PathValidator(root), Map.of())) {
            Files.writeString(copy.root().resolve("a"), "different");
            assertThrows(java.io.IOException.class, copy::verifySourceUnchanged);
            Files.delete(copy.root().resolve("a"));
            assertThrows(java.io.IOException.class, copy::verifySourceUnchanged);
        }
        try (var channel = java.nio.channels.FileChannel.open(source, StandardOpenOption.WRITE)) {
            channel.position(32L * 1024 * 1024); channel.write(java.nio.ByteBuffer.wrap(new byte[]{0}));
        }
        assertThrows(java.io.IOException.class, () -> IsolatedWorkspace.create(new PathValidator(root), Map.of()));
    }

    @Test void candidateCannotBeReplacedByAnInternalSymlinkWithIdenticalContent() throws Exception {
        Files.writeString(root.resolve("a"), "same");
        try (var copy = IsolatedWorkspace.create(new PathValidator(root), Map.of())) {
            Files.writeString(copy.root().resolve("generated"), "same"); Files.delete(copy.root().resolve("a"));
            try { Files.createSymbolicLink(copy.root().resolve("a"), copy.root().resolve("generated")); }
            catch (UnsupportedOperationException | FileSystemException e) { org.junit.jupiter.api.Assumptions.abort("当前平台没有创建符号链接的权限"); }
            assertThrows(java.io.IOException.class, copy::verifySourceUnchanged);
        }
    }
}
