package com.pilot.core.multi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class DiffSandboxTest {
    @Test
    void updatingAFileKeepsItsBaselineAndMovesItToTheEnd() {
        DiffSandbox sandbox = new DiffSandbox("box", "agent", "task", "");
        sandbox.add("src/A.txt", "original", "first", true, RiskLevel.LOW);
        sandbox.add("src/B.txt", null, "new", false, RiskLevel.LOW);
        sandbox.add("src\\A.txt", "first", "second", true, RiskLevel.LOW);

        assertEquals(List.of("src/B.txt", "src\\A.txt"), sandbox.changes().stream().map(SandboxChange::path).toList());
        assertEquals("second", sandbox.currentText("src/A.txt", "disk"));
        assertEquals(DiffSandbox.fingerprint("original"), sandbox.changes().getLast().baseHash());
        assertEquals("disk", sandbox.currentText("missing.txt", "disk"));
        assertNull(sandbox.proposedText(null));
    }

    @Test
    void reviewingAndRemovingChangesRetainsOnlyTheRelevantBaseline() {
        DiffSandbox sandbox = new DiffSandbox("box", "agent", "task", "");
        var original = sandbox.add("a.txt", "original", "changed", true, RiskLevel.LOW);
        sandbox.replaceAll(List.of(original.withStatus(ChangeStatus.ACCEPTED)));
        assertNull(sandbox.add("a.txt", "changed", "original", true, RiskLevel.LOW));
        assertFalse(sandbox.hasChange("a.txt"));
        sandbox.add("a.txt", "new baseline", "changed again", true, RiskLevel.LOW);
        assertEquals(DiffSandbox.fingerprint("new baseline"), sandbox.changes().getFirst().baseHash());
        sandbox.replaceAll(null);
        assertEquals(0, sandbox.fileCount());
    }

    @Test
    void restoredChangesAreIndexedAndDuplicatePathsCannotPartiallyReplaceTheSandbox() {
        DiffSandbox sandbox = new DiffSandbox("box", "agent", "task", "");
        var change = sandbox.add("src/A.txt", null, "proposal", false, RiskLevel.LOW);
        DiffSandbox restored = new DiffSandbox("restored", "agent", "task", "");
        restored.replaceAll(sandbox.changes());
        assertEquals("proposal", restored.proposedText("src\\A.txt"));
        assertEquals(sandbox.candidateHash(), restored.candidateHash());
        assertThrows(IllegalArgumentException.class, () -> restored.replaceAll(List.of(change, change)));
        assertEquals(sandbox.changes(), restored.changes());
    }
}
