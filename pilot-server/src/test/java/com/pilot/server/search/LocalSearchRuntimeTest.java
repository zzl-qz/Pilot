package com.pilot.server.search;

import com.pilot.server.config.PilotDataDir;
import com.pilot.server.config.WebSearchProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class LocalSearchRuntimeTest {
    @TempDir
    Path dataDir;

    @Test
    void staysDownWhenDisabled() {
        var runtime = new LocalSearchRuntime(
                new WebSearchProperties(false, "http://127.0.0.1:9", "", ""),
                new PilotDataDir(dataDir.toString()));

        runtime.start();

        assertFalse(runtime.isRunning());
    }
}
