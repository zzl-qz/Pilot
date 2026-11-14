package com.pilot.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.runtime.bootstrap.BootstrapPlatform;

class ProcessSupportTest {
    @TempDir
    Path directory;

    @Test
    void drainsLargeOutputAndRetainsOnlyTheRequestedPrefix() throws Exception {
        var output = ProcessSupport.captureUtf8(child("output"), Duration.ofSeconds(10), 16);
        assertEquals(7, output.exitCode());
        assertEquals("x".repeat(16), output.output());
        assertEquals(7, ProcessSupport.runDiscarded(child("output"), Duration.ofSeconds(10)));
    }

    @Test
    void timesOutWithoutWaitingForOutputToClose() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertThrows(TimeoutException.class,
                () -> ProcessSupport.captureUtf8(child("sleep"), Duration.ofMillis(400), 64));
            assertThrows(TimeoutException.class,
                () -> ProcessSupport.runDiscarded(child("sleep"), Duration.ofMillis(400)));
        });
    }

    @Test
    void propagatesLaunchFailure() {
        assertThrows(IOException.class,
            () -> ProcessSupport.captureUtf8(new ProcessBuilder(directory.resolve("missing-command").toString()),
                Duration.ofSeconds(1), 64));
    }

    private ProcessBuilder child(String mode) {
        Path java = Path.of(System.getProperty("java.home"), "bin", BootstrapPlatform.current().executable("java"));
        return new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"), Child.class.getName(),
            mode);
    }

    public static class Child {
        public static void main(String[] args) throws InterruptedException {
            if ("sleep".equals(args[0])) {
                Thread.sleep(Duration.ofSeconds(30));
            }
            System.out.print("x".repeat(1_000_000));
            System.exit(7);
        }
    }
}
