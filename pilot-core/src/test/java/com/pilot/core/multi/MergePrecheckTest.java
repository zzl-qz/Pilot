package com.pilot.core.multi;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MergePrecheckTest {

    private static DiffSandbox sandbox(String agent, String task) {
        return new DiffSandbox("sbx-" + agent, agent, task, "");
    }

    private static String numberedFile(int lines) {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= lines; i++) {
            text.append("line-").append(i).append('\n');
        }
        return text.toString();
    }

    /** 把某一行的内容替换掉，模拟一次真实的定位改动。 */
    private static String replaceLine(String text, int line, String value) {
        String[] parts = text.split("\n", -1);
        parts[line - 1] = value;
        return String.join("\n", parts);
    }

    @Test
    void sameFileSameRegionIsAConflict() {
        String base = numberedFile(30);
        var one = sandbox("agentA", "T1");
        var two = sandbox("agentB", "T2");
        one.add("src/A.java", base, replaceLine(base, 10, "A-changed"), true, RiskLevel.MEDIUM);
        two.add("src/A.java", base, replaceLine(base, 10, "B-changed"), true, RiskLevel.MEDIUM);

        MergePrecheck.Report report = MergePrecheck.check(List.of(one, two));

        assertTrue(report.hasConflict());
        assertEquals("src/A.java", report.conflicts().get(0).path());
        assertTrue(report.describe().contains("重叠"));
    }

    @Test
    void sameFileDifferentRegionsCanMerge() {
        String base = numberedFile(40);
        var one = sandbox("agentA", "T1");
        var two = sandbox("agentB", "T2");
        one.add("src/A.java", base, replaceLine(base, 3, "A-changed"), true, RiskLevel.MEDIUM);
        two.add("src/A.java", base, replaceLine(base, 35, "B-changed"), true, RiskLevel.MEDIUM);

        MergePrecheck.Report report = MergePrecheck.check(List.of(one, two));

        assertFalse(report.hasConflict(), report.describe());
    }

    @Test
    void twoAgentsCreatingTheSameFileConflicts() {
        var one = sandbox("agentA", "T1");
        var two = sandbox("agentB", "T2");
        one.add("src/New.java", null, "class New {}", false, RiskLevel.LOW);
        two.add("src/New.java", null, "class New { }", false, RiskLevel.LOW);

        MergePrecheck.Report report = MergePrecheck.check(List.of(one, two));

        assertTrue(report.hasConflict());
        assertTrue(report.describe().contains("都新建同一文件"));
    }

    @Test
    void differentFilesNeverConflict() {
        String base = numberedFile(20);
        var one = sandbox("agentA", "T1");
        var two = sandbox("agentB", "T2");
        one.add("src/A.java", base, replaceLine(base, 5, "A"), true, RiskLevel.MEDIUM);
        two.add("src/B.java", base, replaceLine(base, 5, "B"), true, RiskLevel.MEDIUM);

        assertFalse(MergePrecheck.check(List.of(one, two)).hasConflict());
    }

    @Test
    void mergedChangesKeepEveryPendingFile() {
        String base = numberedFile(10);
        var one = sandbox("agentA", "T1");
        var two = sandbox("agentB", "T2");
        one.add("src/A.java", base, replaceLine(base, 2, "A"), true, RiskLevel.MEDIUM);
        two.add("src/B.java", base, replaceLine(base, 3, "B"), true, RiskLevel.MEDIUM);

        assertEquals(2, MergePrecheck.mergedChanges(List.of(one, two)).size());
    }
}
