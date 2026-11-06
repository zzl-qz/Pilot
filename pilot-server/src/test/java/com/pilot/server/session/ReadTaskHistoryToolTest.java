package com.pilot.server.session;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ReadTaskHistoryToolTest {
    @Test
    void returnsOriginalTextAcrossPagesAndRejectsInvalidRanges() {
        String original = "最初要求：浏览器、PPT、Excel、报告都要交付。\n纠正：不能只更新产品计划。";
        var tool = new ReadTaskHistoryTool(() -> original);
        assertTrue(tool.execute(Map.of("limit", 8)).endsWith(original.substring(0, 8)));
        String rest = tool.execute(Map.of("offset", 8));
        assertTrue(rest.endsWith(original.substring(8)));
        assertTrue(rest.contains("eof=true"));
        assertTrue(tool.execute(Map.of("offset", original.length() + 1)).startsWith("ERROR:"));
        assertTrue(tool.execute(Map.of("limit", 16001)).startsWith("ERROR:"));
    }
}
