package com.pilot.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class QueueLaneTest {

    @Test
    void parkedBlockedAndWrapupDoNotAutoSend() {
        assertEquals("parked", QueueLane.lane("遗留（不在本次范围） | 能力页的插件总览仍失败"));
        assertEquals("blocked", QueueLane.lane("这里点击之后为啥是这样，不太行吧，修复一下"));
        assertEquals("blocked", QueueLane.lane("命名有点奇怪"));
        assertEquals("wrapup", QueueLane.lane("做完记得按照功能拆分多个commit进行提交并推送"));
        assertFalse(QueueLane.sendsAutomatically("遗留（不在本次范围） | 先别做"));
        assertFalse(QueueLane.sendsAutomatically("这里点击之后为啥是这样，不太行吧，修复一下"));
        assertFalse(QueueLane.sendsAutomatically("命名有点奇怪"));
        assertFalse(QueueLane.sendsAutomatically("做完记得按照功能拆分多个commit进行提交并推送"));
    }

    @Test
    void aNamedControlAndOrdinaryWorkStillSend() {
        assertTrue(QueueLane.sendsAutomatically("点「关闭预览」之后外层弹窗也关了，希望只关大图"));
        assertTrue(QueueLane.sendsAutomatically("把删除改成一个 x"));
        assertTrue(QueueLane.sendsAutomatically(""));
    }
}
