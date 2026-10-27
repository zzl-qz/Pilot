package com.pilot.core.multi;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void largeEditFallsBackToReplaceInsteadOfFailing() {
        StringBuilder before = new StringBuilder();
        StringBuilder after = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            before.append("old-").append(i).append('\n');
            after.append("new-").append(i).append('\n');
        }

        String diff = UnifiedDiff.create("big.txt", before.toString(), after.toString());

        assertEquals(3000, UnifiedDiff.countChanges(diff)[1]);
        assertEquals(3000, UnifiedDiff.countChanges(diff)[0]);
    }
}
