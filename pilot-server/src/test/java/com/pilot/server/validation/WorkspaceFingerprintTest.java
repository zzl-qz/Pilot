package com.pilot.server.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceFingerprintTest {
    @TempDir
    Path workspace;

    @Test
    void fingerprintsBinaryFileAndDetectsByteChanges() throws Exception {
        Path artifact = workspace.resolve("artifact.png");
        Files.write(artifact, new byte[] {(byte)0x89, 'P', 'N', 'G', 0, (byte)0xff});
        String before = WorkspaceFingerprint.of(workspace, List.of("artifact.png"));
        Files.write(artifact, new byte[] {(byte)0x89, 'P', 'N', 'G', 1, (byte)0xff});
        String after = WorkspaceFingerprint.of(workspace, List.of("artifact.png"));

        assertNotEquals(before, after);
        assertFalse("UNREADABLE".equals(before));
        assertFalse("UNREADABLE".equals(after));
    }
}
