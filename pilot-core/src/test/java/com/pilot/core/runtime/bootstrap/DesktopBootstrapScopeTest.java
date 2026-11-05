package com.pilot.core.runtime.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopBootstrapScopeTest {
    @TempDir
    Path root;

    @Test
    void missingOptionalBridgeNeverBuildsMobileSourcesOrDownloadsMobileToolchains() throws Exception {
        Files.createDirectories(root.resolve("pilot-mobile/bridge/src"));
        Path cache = root.resolve(".pilot/bootstrap");
        var messages = new ArrayList<String>();
        var result = new SourceEnvironment(root, cache, false, messages::add).prepareMobile(null);
        assertFalse(result.ready());
        assertTrue(result.message().contains("尚未打包"));
        try (var files = Files.walk(cache)) {
            assertFalse(files.anyMatch(path -> path.toString().contains("jdk21") || path.toString().contains("gradle")
                || path.toString().endsWith(".archive")));
        }
    }
}
