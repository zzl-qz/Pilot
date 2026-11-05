package com.pilot.core.runtime.bootstrap;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BootstrapLockTest {
    @TempDir
    Path directory;

    @Test
    void waitingHasDeadlineAndLockIsReusableAfterOwnerCloses() throws Exception {
        Path file = directory.resolve("prepare.lock");
        try (var first = BootstrapLock.acquire(file, Duration.ofSeconds(1))) {
            assertThrows(IOException.class, () -> BootstrapLock.acquire(file, Duration.ofMillis(20)));
        }
        try (var next = BootstrapLock.acquire(file, Duration.ofSeconds(1))) {
            assertNotNull(next);
        }
    }
}
