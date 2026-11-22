package com.pilot.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolOutputArchiveReferenceTest {
    @Test
    void recognizesOnlyTrailingArchiveReferences() {
        String reference = "[工具输出存档 run_id=run-1 call_id=call_2；使用 read_tool_output]";

        assertEquals(reference, ToolOutputArchiveReference.trailingReference("result\n" + reference + " \n"));
        assertNull(ToolOutputArchiveReference.trailingReference("文档示例 " + reference + " 后面还有正文"));
        assertNull(ToolOutputArchiveReference.trailingReference("[工具输出存档 run_id= call_id=]"));
        assertNull(ToolOutputArchiveReference.trailingReference(null));
    }

    @Test
    void recognizesUnarchivedMarkerAtEndOnly() {
        String marker = ToolOutputArchiveReference.UNARCHIVED_MARKER;

        assertTrue(ToolOutputArchiveReference.isUnarchived("result\n" + marker + "  "));
        assertFalse(ToolOutputArchiveReference.isUnarchived("文档提到 " + marker + " 但后面还有正文"));
        assertFalse(ToolOutputArchiveReference.isUnarchived(null));
    }
}
