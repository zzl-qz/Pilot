package com.pilot.core.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathValidatorTest {
    @TempDir
    Path workspace;

    @Test
    void resolvesRelativePathInsideWorkspace() throws IOException {
        Files.createDirectories(workspace.resolve("pilot-core/src"));
        PathValidator validator = new PathValidator(workspace);

        Path resolved = validator.resolve("pilot-core/src");

        assertEquals(workspace.resolve("pilot-core/src").toRealPath(), resolved);
        assertEquals("pilot-core/src", validator.display(resolved));
    }

    @Test
    void allowsParentSegmentsThatStayInsideWorkspace() throws IOException {
        Files.createDirectories(workspace.resolve("a/b"));
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        PathValidator validator = new PathValidator(workspace);

        assertEquals(workspace.resolve("pom.xml").toRealPath(), validator.resolve("a/b/../../pom.xml"));
    }

    @Test
    void rejectsPathTraversal() {
        PathValidator validator = new PathValidator(workspace);

        assertThrows(WorkspaceViolationException.class, () -> validator.resolve("../../etc/passwd"));
        assertThrows(WorkspaceViolationException.class, () -> validator.resolve("a/../../outside.txt"));
    }

    @Test
    void rejectsAbsolutePathOutsideWorkspace() {
        PathValidator validator = new PathValidator(workspace);

        Path outside = workspace.getParent().resolve("outside.txt");
        assertThrows(WorkspaceViolationException.class, () -> validator.resolve(outside.toString()));
    }

    @Test
    void rejectsNonexistentChildBehindExternalSymlink() throws IOException {
        Path outside = workspace.getParent().resolve("outside-target");
        Files.createDirectories(outside);
        Path link = workspace.resolve("external-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            assumeTrue(false, "当前账户无法创建符号链接：" + e.getMessage());
            return;
        }

        PathValidator validator = new PathValidator(workspace);
        assertThrows(WorkspaceViolationException.class, () -> validator.resolve("external-link/new-file.txt"));
    }

    @Test
    void rejectsDanglingSymlinksIncludingMissingChildren() throws IOException {
        Path link = workspace.resolve("dangling");
        symlink(link, workspace.getParent().resolve("missing-outside"));
        PathValidator validator = new PathValidator(workspace);
        assertThrows(WorkspaceViolationException.class, () -> validator.resolve("dangling"));
        assertThrows(WorkspaceViolationException.class, () -> validator.resolve("dangling/new.txt"));
    }

    @Test
    void acceptsAbsolutePathsThroughWorkspaceAlias() throws IOException {
        Path real = Files.createDirectory(workspace.resolve("real"));
        Path alias = workspace.resolve("alias");
        symlink(alias, real);
        Files.writeString(real.resolve("existing.txt"), "content");
        PathValidator validator = new PathValidator(alias);
        assertEquals(real.resolve("existing.txt").toRealPath(),
            validator.resolve(alias.resolve("existing.txt").toString()));
        assertEquals(real.toRealPath().resolve("new/child.txt"),
            validator.resolve(alias.resolve("new/child.txt").toString()));
    }

    @Test
    void resolvesInternalSymlinkToCanonicalFile() throws IOException {
        Path target = Files.writeString(workspace.resolve("target.txt"), "content");
        symlink(workspace.resolve("link.txt"), target);
        assertEquals(target.toRealPath(), new PathValidator(workspace).resolve("link.txt"));
    }

    private static void symlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            assumeTrue(false, "当前账户无法创建符号链接：" + e.getMessage());
        }
    }

    @Test
    void rejectsBlankPath() {
        PathValidator validator = new PathValidator(workspace);

        assertThrows(IllegalArgumentException.class, () -> validator.resolve("  "));
        assertThrows(IllegalArgumentException.class, () -> validator.resolve(null));
    }
}
