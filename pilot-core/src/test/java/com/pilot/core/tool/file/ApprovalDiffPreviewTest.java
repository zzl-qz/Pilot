package com.pilot.core.tool.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ApprovalDiffPreviewTest {
    @Test
    void buildsUnifiedPreviewForEditCreateAndPatch() {
        Map<String, Object> edit = ApprovalDiffPreview.forEdit(Map.of(
            "path", "src/App.tsx",
            "old_string", "const a = 1;",
            "new_string", "const a = 2;"
        ));
        assertEquals("src/App.tsx", edit.get("path"));
        String editDiff = String.valueOf(edit.get("previewDiff"));
        assertTrue(editDiff.contains("--- a/src/App.tsx"));
        assertTrue(editDiff.contains("-const a = 1;"));
        assertTrue(editDiff.contains("+const a = 2;"));

        Map<String, Object> created = ApprovalDiffPreview.forCreate(Map.of(
            "path", "notes.txt",
            "content", "hello\nworld"
        ));
        assertEquals("notes.txt", created.get("path"));
        String createDiff = String.valueOf(created.get("previewDiff"));
        assertTrue(createDiff.contains("+++ b/notes.txt"));
        assertTrue(createDiff.contains("+hello"));

        Map<String, Object> patch = ApprovalDiffPreview.forPatch(Map.of(
            "patch", "*** Begin Patch\n*** Update File: a.txt\n-old\n+new\n*** End Patch"
        ));
        assertTrue(String.valueOf(patch.get("previewDiff")).contains("-old"));
    }

    @Test
    void truncatesLargePreviewSafely() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            huge.append("line ").append(i).append(": ").append("x".repeat(40)).append('\n');
        }
        Map<String, Object> created = ApprovalDiffPreview.forCreate(Map.of("path", "big.txt", "content", huge.toString()));
        String diff = String.valueOf(created.get("previewDiff"));
        assertFalse(diff.isEmpty());
        assertTrue(diff.contains("差异已截断"));
        assertTrue(diff.length() <= 1900);
    }
}
