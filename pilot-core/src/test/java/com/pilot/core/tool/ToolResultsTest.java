package com.pilot.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 结果开头的标记会写进历史会话，模型和前端都认这些字，不能变。 */
class ToolResultsTest {

    @Test
    void markersStayStable() {
        assertEquals("ERROR", ToolResults.ERROR);
        assertEquals("ERROR: 权限拒绝", ToolResults.PERMISSION_DENIED);
        assertEquals("ERROR: 用户拒绝", ToolResults.USER_DENIED);
        assertEquals("ERROR: 结果未确认", ToolResults.RESULT_UNKNOWN);
        assertEquals("ERROR: 命令已结束，但无法完整确认其工作区变更", ToolResults.CHANGE_TRACKING_INCOMPLETE);
        assertEquals("SKIPPED:", ToolResults.SKIPPED);
        assertEquals("SKIPPED: 用户已取消", ToolResults.skipped("用户已取消"));
    }

    @Test
    void recognisesEachKind() {
        assertTrue(ToolResults.isError("ERROR: 文件不存在: a.txt"));
        assertTrue(ToolResults.isError("ERROR"));
        assertFalse(ToolResults.isError("已写入 a.txt"));
        assertFalse(ToolResults.isError(" ERROR: 前面有空格不算"));

        assertTrue(ToolResults.isDenied("ERROR: 权限拒绝：规则不允许"));
        assertTrue(ToolResults.isDenied("ERROR: 用户拒绝执行 edit_file，文件未修改。"));
        assertFalse(ToolResults.isDenied("ERROR: 文件不存在: a.txt"));

        assertTrue(ToolResults.isSkipped(ToolResults.skipped("已暂停")));
        assertFalse(ToolResults.isSkipped("ERROR: 已暂停"));

        assertTrue(ToolResults.isResultUnknown("ERROR: 结果未确认：连接中断"));
        assertTrue(ToolResults.isChangeTrackingIncomplete("ERROR: 命令已结束，但无法完整确认其工作区变更；不得据此宣称验证通过：x"));
    }

    @Test
    void deniedAndSpecialResultsAreStillErrors() {
        assertTrue(ToolResults.isError(ToolResults.PERMISSION_DENIED));
        assertTrue(ToolResults.isError(ToolResults.USER_DENIED));
        assertTrue(ToolResults.isError(ToolResults.RESULT_UNKNOWN));
        assertTrue(ToolResults.isError(ToolResults.CHANGE_TRACKING_INCOMPLETE));
        assertFalse(ToolResults.isError(ToolResults.skipped("x")));
    }

    @Test
    void nullIsNeverAMatch() {
        assertFalse(ToolResults.isError(null));
        assertFalse(ToolResults.isDenied(null));
        assertFalse(ToolResults.isSkipped(null));
        assertFalse(ToolResults.isResultUnknown(null));
        assertFalse(ToolResults.isChangeTrackingIncomplete(null));
    }
}
