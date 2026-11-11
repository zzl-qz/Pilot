package com.pilot.core.multi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class UnifiedDiffTest {

    @Test
    void createsGitCompatiblePatch() {
        String before = "a\nb\nc\nd\ne\nf\ng\nh\n";
        String after = "a\nb\nc\nCHANGED\ne\nf\ng\nh\n";

        String diff = UnifiedDiff.create("src/A.java", before, after);

        assertTrue(diff.startsWith("diff --git a/src/A.java b/src/A.java\n"));
        assertTrue(diff.contains("--- a/src/A.java"));
        assertTrue(diff.contains("+++ b/src/A.java"));
        assertTrue(diff.contains("-d\n+CHANGED"));
        int[] counts = UnifiedDiff.countChanges(diff);
        assertEquals(1, counts[0]);
        assertEquals(1, counts[1]);
    }

    @Test
    void marksNewFiles() {
        String diff = UnifiedDiff.create("src/New.java", null, "line1\nline2\n");

        assertTrue(diff.contains("new file mode 100644"));
        assertTrue(diff.contains("--- /dev/null"));
        assertTrue(diff.contains("@@ -0,0 +1,2 @@"));
        assertEquals(2, UnifiedDiff.countChanges(diff)[0]);
    }

    @Test
    void identicalContentProducesNoDiff() {
        assertEquals("", UnifiedDiff.create("a.txt", "same\n", "same\n"));
    }

    @Test
    void crlfAndLfAreTreatedAsSameLines() {
        String diff = UnifiedDiff.create("a.txt", "a\r\nb\r\n", "a\nb\n");

        assertEquals("", diff, "换行符差异不应当成内容改动");
    }

    @Test
    void changedRangesPointAtTheRealLines() {
        String before = "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n";
        String after = "1\n2\n3\n4\nFIVE\n6\n7\n8\n9\n10\n";

        List<int[]> ranges = UnifiedDiff.oldRanges(UnifiedDiff.create("a.txt", before, after));

        assertEquals(1, ranges.size());
        assertEquals(5, ranges.get(0)[0]);
        assertEquals(5, ranges.get(0)[1]);
    }

    @Test
    void insertionIsTreatedAsAPoint() {
        String before = "1\n2\n3\n";
        String after = "1\n2\nINSERTED\n3\n";

        List<int[]> ranges = UnifiedDiff.oldRanges(UnifiedDiff.create("a.txt", before, after));

        assertFalse(ranges.isEmpty());
        assertEquals(2, ranges.get(0)[0]);
    }

    @Test
    void largeFileWithTwoDistantChangesKeepsBothHunksSmall() {
        String middle = IntStream.range(2, 3000).mapToObj(i -> "line " + i).collect(Collectors.joining("\n"));
        String before = "old first\n" + middle + "\nold last\n";
        String after = "new first\n" + middle + "\nnew last\n";

        String diff = UnifiedDiff.create("big.txt", before, after);

        assertArrayEquals(new int[] {2, 2}, UnifiedDiff.countChanges(diff));
        List<int[]> ranges = UnifiedDiff.oldRanges(diff);
        assertEquals(2, ranges.size());
        assertArrayEquals(new int[] {1, 1}, ranges.get(0));
        assertArrayEquals(new int[] {3000, 3000}, ranges.get(1));
    }

    @Test
    void largeEditFallsBackToReplaceInsteadOfFailing() {
        StringBuilder before = new StringBuilder();
        StringBuilder after = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            before.append(i == 1500 ? "common" : "old-" + i).append('\n');
            after.append(i == 1500 ? "common" : "new-" + i).append('\n');
        }

        String diff = UnifiedDiff.create("big.txt", before.toString(), after.toString());

        assertArrayEquals(new int[] {3000, 3000}, UnifiedDiff.countChanges(diff));
        assertTrue(diff.contains("-common\n"));
        assertTrue(diff.contains("+common\n"));
    }
}
