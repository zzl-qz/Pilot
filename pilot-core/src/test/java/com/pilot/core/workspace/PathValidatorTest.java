package com.pilot.core.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PathValidatorTest {
    @TempDir
    Path workspace;

    @Test
    void resolvesRelativePathInsideWorkspace() throws IOException {
        Files.createDirectories(workspace.resolve("pilot-core/src"));
        var validator = new PathValidator(workspace);

        Path resolved = validator.resolve("pilot-core/src");

        assertEquals(workspace.resolve("pilot-core/src").toRealPath(), resolved);
        assertEquals("pilot-core/src", validator.display(resolved));
    }

    @Test
    void allowsParentSegmentsThatStayInsideWorkspace() throws IOException {
        Files.createDirectories(workspace.resolve("a/b"));
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        var validator = new PathValidator(workspace);

        assertEquals(workspace.resolve("pom.xml").toRealPath(), validator.resolve("a/b/../../pom.xml"));
    }

    @Test
    void rejectsPathTraversal() {
        var validator = new PathValidator(workspace);

        assertThrows(WorkspaceViolationException.class, () -> validator.resolve("../../etc/passwd"));
        assertThrows(WorkspaceViolationException.class, () -> validator.resolve("a/../../outside.txt"));
    }

    @Test
    void rejectsAbsolutePathOutsideWorkspace() {
        var validator = new PathValidator(workspace);

        Path outside = workspace.getParent().resolve("outside.txt");
        assertThrows(WorkspaceViolationException.class, () -> validator.resolve(outside.toString()));
    }

    @Test
    void rejectsBlankPath() {
        var validator = new PathValidator(workspace);

        assertThrows(IllegalArgumentException.class, () -> validator.resolve("  "));
        assertThrows(IllegalArgumentException.class, () -> validator.resolve(null));
    }
}
