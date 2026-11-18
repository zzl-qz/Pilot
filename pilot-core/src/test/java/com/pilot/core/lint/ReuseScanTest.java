package com.pilot.core.lint;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ReuseScanTest {
    private static final String BLOCK = """
        String left = orderId == null ? "" : orderId.strip();
        String right = buyer == null ? "" : buyer.strip();
        if (left.isEmpty()) return right;
        return left + " " + right;
        """;

    @Test
    void fourLinesStayQuietUntilTheyShowUpThreeTimes() {
        assertTrue(ReuseScan.scan("A.java", "", BLOCK, List.of()).isEmpty());
        String once = BLOCK + "if (ready) return left;\nwhile (open) break;\n";
        assertTrue(ReuseScan.scan("A.java", "", once + BLOCK, List.of()).isEmpty());
        List<String> hit = ReuseScan.scan("A.java", "",
            once + BLOCK + "for (;;) break;\nthrow new IllegalStateException(reason);\n" + BLOCK, List.of());
        assertTrue(hit.stream().anyMatch(line -> line.contains("短重复")), hit.toString());
    }

    @Test
    void trivialReturnsStayQuietAndARealStatementRepeats() {
        assertTrue(ReuseScan.scan("A.java", "", "    return 1;\n".repeat(4), List.of()).isEmpty());
        List<String> hit = ReuseScan.scan("A.java", "", "order.setStatus(paid);\n".repeat(4), List.of());
        assertTrue(hit.stream().anyMatch(line -> line.contains("setStatus")), hit.toString());
    }
}
