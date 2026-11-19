package com.pilot.core.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadTrackerTest {
    @TempDir
    Path directory;

    @Test
    void sameSizeReplacementIsStale() throws Exception {
        Path file = directory.resolve("note.txt");
        Files.writeString(file, "aaaa");
        ReadTracker tracker = new ReadTracker();
        tracker.observed(file);
        FileTime modified = Files.getLastModifiedTime(file);

        Files.writeString(file, "bbbb");
        Files.setLastModifiedTime(file, modified);

        assertFalse(tracker.isFresh(file));
        tracker.observed(file);
        assertTrue(tracker.isFresh(file));
    }
}
