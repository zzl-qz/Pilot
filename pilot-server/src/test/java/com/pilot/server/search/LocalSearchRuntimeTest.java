package com.pilot.server.search;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.server.config.PilotDataDir;
import com.pilot.server.config.WebSearchProperties;

class LocalSearchRuntimeTest {
    @TempDir
    Path dataDir;

    @Test
    void staysDownWhenDisabled() {
        LocalSearchRuntime runtime = new LocalSearchRuntime(
            new WebSearchProperties(false, "http://127.0.0.1:9", "", ""), new PilotDataDir(dataDir.toString()));

        runtime.start();

        assertFalse(runtime.isRunning());
    }
}
