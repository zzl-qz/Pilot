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

    @Test
    void insertingOneGuardLineIntoExistingSimilarMethodsDoesNotFalsePositive() {
        String before = """
            private static String presentationText(Path file) throws IOException {
                StringBuilder out = new StringBuilder();
                try (InputStream in = Files.newInputStream(file); XMLSlideShow deck = new XMLSlideShow(in)) {
                    for (var slide : deck.getSlides()) {
                        out.append(slide.getSlideNumber());
                    }
                }
                return out.toString();
            }
            private static String documentText(Path file) throws IOException {
                StringBuilder out = new StringBuilder();
                try (InputStream in = Files.newInputStream(file); XWPFDocument doc = new XWPFDocument(in)) {
                    for (var element : doc.getBodyElements()) {
                        out.append(element.toString());
                    }
                }
                return out.toString();
            }
            """;
        String after = """
            private static String presentationText(Path file) throws IOException {
                StringBuilder out = new StringBuilder();
                hardenZip();
                try (InputStream in = Files.newInputStream(file); XMLSlideShow deck = new XMLSlideShow(in)) {
                    for (var slide : deck.getSlides()) {
                        out.append(slide.getSlideNumber());
                    }
                }
                return out.toString();
            }
            private static String documentText(Path file) throws IOException {
                StringBuilder out = new StringBuilder();
                hardenZip();
                try (InputStream in = Files.newInputStream(file); XWPFDocument doc = new XWPFDocument(in)) {
                    for (var element : doc.getBodyElements()) {
                        out.append(element.toString());
                    }
                }
                return out.toString();
            }
            """;
        List<String> guardInsertHits = ReuseScan.scan("OfficeSupport.java", before, after, List.of());
        assertTrue(guardInsertHits.isEmpty(), "向既有相似方法各插入一行守卫调用不得误报换名复制: " + guardInsertHits);

        String pastedDuplicate = before + """
            private static String archiveText(Path file) throws IOException {
                StringBuilder out = new StringBuilder();
                try (InputStream in = Files.newInputStream(file); ZipArchive pack = new ZipArchive(in)) {
                    for (var item : pack.getEntries()) {
                        out.append(item.toString());
                    }
                }
                return out.toString();
            }
            """;
        List<String> realCopyHits = ReuseScan.scan("OfficeSupport.java", before, pastedDuplicate, List.of());
        assertTrue(realCopyHits.stream().anyMatch(line -> line.contains("换名复制")),
            "真正新增粘贴一整段相似方法时仍应检出换名复制: " + realCopyHits);
    }
}
