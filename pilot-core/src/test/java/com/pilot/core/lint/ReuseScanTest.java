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
        assertTrue(ReuseScan.scan("A.java", "", "    try {\n    } else {\n".repeat(4), List.of()).isEmpty(),
            "语法块关键字 try {/} else { 不应触发单行重复告警");
        List<String> hit = ReuseScan.scan("A.java", "", "order.setStatus(paid);\n".repeat(4), List.of());
        assertTrue(hit.stream().anyMatch(line -> line.contains("setStatus")), hit.toString());
    }

    @Test
    void overlappingGuardChainDoesNotTriggerSelfMatchWhileDisjointCopyStillDoes() {
        String guardChain = """
            long cacheRead(JsonNode usage, JsonNode details) {
                long direct = readPositive(usage, "cached_tokens");
                if (direct >= 0) return direct;
                long detail = readPositive(details, "cached_tokens");
                if (detail >= 0) return detail;
                long legacy = readPositive(usage, "prompt_cache_hit_tokens");
                if (legacy >= 0) return legacy;
                long fallback = readPositive(details, "cache_read_input_tokens");
                if (fallback >= 0) return fallback;
                return 0L;
            }
            """;
        List<String> guardHits = ReuseScan.scan("ModelUsageParser.java", "", guardChain, List.of());
        assertTrue(guardHits.isEmpty(), "相邻重叠窗口的早返回守卫链不应自我匹配误报换名复制: " + guardHits);

        String disjointCopy = """
            long parseFirst(JsonNode a, JsonNode b) {
                long x = readPositive(a, "k1");
                if (x >= 0) {
                    return x;
                }
                long y = readPositive(b, "k2");
                if (y >= 0) {
                    return y;
                }
                return 0L;
            }
            long parseSecond(JsonNode c, JsonNode d) {
                long p = readPositive(c, "k3");
                if (p >= 0) {
                    return p;
                }
                long q = readPositive(d, "k4");
                if (q >= 0) {
                    return q;
                }
                return 0L;
            }
            """;
        List<String> hit = ReuseScan.scan("ModelUsageParser.java", "", disjointCopy, List.of());
        assertTrue(hit.stream().anyMatch(line -> line.contains("换名复制")), "同文件内两段互不重叠的 6 行重复逻辑仍应准确检出: " + hit);
    }
}
