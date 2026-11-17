package com.pilot.core.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.quality.CodebaseAuditor.AuditSnapshot;
import com.pilot.core.quality.CodebaseAuditor.DeltaReport;

class CodebaseAuditorTest {

    @Test
    void auditsExistingLegacyCodeSkipsTestsAndComputesBeforeAfterDelta(@TempDir Path workspace) throws IOException {
        Path serviceA = workspace.resolve("src/main/java/legacy/OrderService.java");
        Path serviceB = workspace.resolve("src/main/java/legacy/RefundService.java");
        Path testFile = workspace.resolve("src/test/java/legacy/OrderServiceTest.java");
        Path nodeMod = workspace.resolve("node_modules/pkg/index.js");
        Files.createDirectories(serviceA.getParent());
        Files.createDirectories(testFile.getParent());
        Files.createDirectories(nodeMod.getParent());

        Files.writeString(serviceA, """
            package legacy;
            import java.io.FileInputStream;
            import java.util.List;
            public class OrderService {
                public int settle(List<Order> orders, int rate) throws Exception {
                    int expireSeconds = 86400;
                    String state = "PAID";
                    FileInputStream leak = new FileInputStream("cfg.txt");
                    for (Order o : orders) {
                        orderDao.findById(o.id());
                    }
                    int settled = calcRebate(orders, rate, state);
                    return settled + expireSeconds + leak.available();
                }

                int calcRebate(List<Order> orders, int rate, String tag) {
                    if (orders == null) {
                        return 0;
                    }
                    int total = orders.size() * rate;
                    if (total > 100) {
                        total = total - 10;
                    }
                    notifyLedger(tag, total);
                    return Math.max(total, 0);
                }
            }
            """);

        Files.writeString(serviceB, """
            package legacy;
            import java.util.List;
            public class RefundService {
                public int refund(List<Refund> items, int factor) {
                    String status = "APPROVED";
                    return calcRefundAmount(items, factor, status);
                }

                int calcRefundAmount(List<Refund> items, int factor, String label) {
                    if (items == null) {
                        return 0;
                    }
                    int amount = items.size() * factor;
                    if (amount > 100) {
                        amount = amount - 10;
                    }
                    recordAudit(label, amount);
                    return Math.max(amount, 0);
                }
            }
            """);

        // 测试文件与 node_modules 中的魔法值不得混入生产代码审计账本
        Files.writeString(testFile, "class OrderServiceTest { int x = 99999; }\n");
        Files.writeString(nodeMod, "const magic = 77777;\n");

        AuditSnapshot baseline = CodebaseAuditor.audit(workspace);
        assertEquals(2, baseline.scannedFiles());
        assertTrue(baseline.duplicateBlocks() >= 1, "Expected cross-file structural clone: " + baseline.items());
        assertTrue(baseline.magicLiterals() >= 2, "Expected magic literals: " + baseline.items());
        assertTrue(baseline.perfHazards() >= 1, "Expected loop N+1 hazard: " + baseline.items());
        assertTrue(baseline.boundaryHazards() >= 1, "Expected unclosed stream hazard: " + baseline.items());
        assertEquals("src/main/java/legacy/OrderService.java", baseline.hotspots().getFirst().path());

        // 重构收敛：提取公共结算逻辑、具名常量、批量查询与 try-with-resources
        Path shared = workspace.resolve("src/main/java/legacy/SettlementAmounts.java");
        Files.writeString(shared, """
            package legacy;
            final class SettlementAmounts {
                private static final int REBATE_THRESHOLD = 100;
                private static final int REBATE_DEDUCTION = 10;

                private SettlementAmounts() {
                }

                static int compute(int count, int multiplier) {
                    int value = count * multiplier;
                    if (value > REBATE_THRESHOLD) {
                        value = value - REBATE_DEDUCTION;
                    }
                    return Math.max(value, 0);
                }
            }
            """);

        Files.writeString(serviceA, """
            package legacy;
            import java.io.InputStream;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.util.List;
            public class OrderService {
                private static final int EXPIRE_SECONDS = 86400;
                private static final String STATUS_PAID = "PAID";

                public int settle(List<Order> orders, int rate) throws Exception {
                    if (orders == null || orders.isEmpty()) {
                        return 0;
                    }
                    orderDao.findAllByIds(orders);
                    int total = SettlementAmounts.compute(orders.size(), rate);
                    notifyLedger(STATUS_PAID, total);
                    try (InputStream in = Files.newInputStream(Path.of("cfg.txt"))) {
                        return total + EXPIRE_SECONDS + in.available();
                    }
                }
            }
            """);

        Files.writeString(serviceB, """
                        package legacy;
                        import java.util.List;
            import java.util.List;
                        public class RefundService {
                            private static final String STATUS_APPROVED = "APPROVED";

                            public int refund(List<Refund> items, int factor) {
                                if (items == null || items.isEmpty()) {
                                    return 0;
                                }
                                int amount = SettlementAmounts.compute(items.size(), factor);
                                recordAudit(STATUS_APPROVED, amount);
                                return amount;
                            }
                        }
                        """);

        AuditSnapshot after = CodebaseAuditor.audit(workspace);
        DeltaReport delta = CodebaseAuditor.compare(baseline, after);

        assertEquals(3, after.scannedFiles());
        assertEquals(0, after.totalIssues(), "Refactored code should have 0 smells: " + after.items());
        assertTrue(delta.improved());
        assertFalse(delta.regressed());
        assertTrue(delta.duplicatesDelta() < 0);
        assertTrue(delta.magicDelta() < 0);
        assertTrue(delta.perfDelta() < 0);
        assertTrue(delta.boundaryDelta() < 0);
        assertTrue(delta.renderSummary().contains("-> 0"));
    }

    @Test
    void newlyDeliveredQualityAndToolClassesPassOwnStaticAuditorWithZeroSmells() {
        Path moduleRoot = Path.of("").toAbsolutePath();
        for (String selfFile : List.of("src/main/java/com/pilot/core/quality/PerformanceBoundaryScanner.java",
            "src/main/java/com/pilot/core/quality/CodebaseAuditor.java",
            "src/main/java/com/pilot/core/quality/RefactorEvidenceGate.java",
            "src/main/java/com/pilot/core/tool/AuditCodebaseTool.java")) {
            if (Files.isRegularFile(moduleRoot.resolve(selfFile))) {
                AuditSnapshot selfSnap = CodebaseAuditor.audit(moduleRoot, selfFile);
                assertEquals(0, selfSnap.totalIssues(), selfFile + " has self-smells: " + selfSnap.items());
            }
        }
    }
}
