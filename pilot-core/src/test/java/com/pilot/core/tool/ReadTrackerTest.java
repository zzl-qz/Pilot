package com.pilot.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

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

    @Test
    void mergesAdjacentRangesAndComputesOverlapAccurately() throws Exception {
        Path file = directory.resolve("Large.java");
        Files.writeString(file, "line\n".repeat(1000));
        ReadTracker tracker = new ReadTracker();
        String stamp = tracker.observationStamp(file);

        tracker.recordDelivered(file, stamp, 1, 200, 1000);
        tracker.recordDelivered(file, stamp, 201, 400, 1000);
        tracker.recordDelivered(file, stamp, 601, 800, 1000);

        List<ReadGuard.LineRange> ranges = tracker.deliveredRanges(file, stamp);
        assertEquals(List.of(new ReadGuard.LineRange(1, 400), new ReadGuard.LineRange(601, 800)), ranges);
        assertFalse(tracker.isFullyDelivered(file, stamp));

        ReadGuard.ReadCoverage fullOverlap = tracker.checkDelivered(file, stamp, 10, 350);
        assertEquals(1.0, fullOverlap.overlapRatio(), 0.001);

        ReadGuard.ReadCoverage slightOverlap = tracker.checkDelivered(file, stamp, 381, 200);
        assertEquals(0.10, slightOverlap.overlapRatio(), 0.001);

        ReadGuard.ReadCoverage uncovered = tracker.checkDelivered(file, stamp, 801, 200);
        assertEquals(0.0, uncovered.overlapRatio(), 0.001);

        tracker.recordDelivered(file, stamp, 401, 600, 1000);
        tracker.recordDelivered(file, stamp, 801, 1000, 1000);
        assertTrue(tracker.isFullyDelivered(file, stamp));
        assertEquals(1.0, tracker.checkDelivered(file, stamp, -100, 100).overlapRatio(), 0.001);
    }

    @Test
    void invalidateDeliveredViewsClearsSlicesWhilePreservingEditSafety() throws Exception {
        Path file = directory.resolve("App.java");
        Files.writeString(file, "class App {}\n");
        ReadTracker tracker = new ReadTracker();
        tracker.observed(file);
        String stamp = tracker.observationStamp(file);
        tracker.recordDelivered(file, stamp, 1, 1, 1);

        assertEquals(1.0, tracker.checkDelivered(file, stamp, 1, 400).overlapRatio(), 0.001);
        assertTrue(tracker.isFullyDelivered(file, stamp));

        int beforeEpoch = tracker.viewEpoch();
        tracker.invalidateDeliveredViews();

        assertEquals(beforeEpoch + 1, tracker.viewEpoch());
        assertEquals(0.0, tracker.checkDelivered(file, stamp, 1, 400).overlapRatio(), 0.001);
        assertFalse(tracker.isFullyDelivered(file, stamp));
        assertTrue(tracker.isObserved(file), "压缩清视图后必须保留已读登记，不破坏先读后改");
        assertTrue(tracker.isFresh(file), "文件本身未变时 isFresh 仍应为 true");
    }

    @Test
    void invalidateDeliveredViewClearsSingleFileWithoutAffectingOthers() throws Exception {
        Path a = directory.resolve("A.java");
        Path b = directory.resolve("B.java");
        Files.writeString(a, "class A {}\n");
        Files.writeString(b, "class B {}\n");
        ReadTracker tracker = new ReadTracker();
        tracker.observed(a);
        tracker.observed(b);
        String stampA = tracker.observationStamp(a);
        String stampB = tracker.observationStamp(b);
        tracker.recordDelivered(a, stampA, 1, 1, 1);
        tracker.recordDelivered(b, stampB, 1, 1, 1);

        tracker.invalidateDeliveredView(a);

        assertEquals(0.0, tracker.checkDelivered(a, stampA, 1, 10).overlapRatio(), 0.001);
        assertEquals(1.0, tracker.checkDelivered(b, stampB, 1, 10).overlapRatio(), 0.001);
        assertTrue(tracker.isObserved(a), "单文件清视图后仍应保留已读安全记录");
    }
}
