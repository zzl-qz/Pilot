package com.pilot.core.quality;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class RefactorEvidenceGateTest {

    @Test
    void boundaryGoalWithoutTestChangesIsRejectedUntilTestIsAdded() {
        RefactorEvidenceGate gate = new RefactorEvidenceGate("完善订单解析器的边界条件与异常鲁棒性");
        assertTrue(gate.intent().boundary());

        String cleanCode = """
            package demo;
            public class OrderParser {
                public int parseCount(String raw) {
                    if (raw == null || raw.isBlank()) {
                        return 0;
                    }
                    try {
                        return Math.max(0, Integer.parseInt(raw.strip()));
                    } catch (NumberFormatException ignored) {
                        return 0;
                    }
                }
            }
            """;
        Map<String, TextChange> changes = Map.of("src/main/java/demo/OrderParser.java",
            new TextChange("package demo;\npublic class OrderParser {}\n", cleanCode));

        // 仅修改业务代码、未修改任何测试文件 -> 必须拦截要求补充边界单测
        String missingTests =
            gate.evaluate(null, Set.of("src/main/java/demo/OrderParser.java"), changes, true, "已完善空值与非法数值边界处理。");
        assertNotNull(missingTests);
        assertTrue(missingTests.contains("未新增或更新任何边界单元测试"), missingTests);

        // 同时新增并通过了边界单元测试 -> 放行
        String withTests = gate.evaluate(null,
            Set.of("src/main/java/demo/OrderParser.java", "src/test/java/demo/OrderParserTest.java"), changes, true,
            "已完善空值与非法数值边界处理并补充单测。");
        assertNull(withTests);
    }

    @Test
    void cleanupIntentDoesNotInventAPrivateSmellScanner() {
        RefactorEvidenceGate gate = new RefactorEvidenceGate("重构老项目 OrderService，清理魔法值并精简代码");
        assertTrue(gate.intent().cleanup());
        assertNull(gate.evaluate(null, Set.of("src/main/OrderService.java"),
            Map.of("src/main/OrderService.java",
                new TextChange("class A { int ttl = 86400; }\n", "class A { int ttl = 86400; int extra = 43200; }\n")),
            true, "我已经重构得非常完美了！"));
    }

    @Test
    void performanceAntiBluffCatchesHandWavingAndRemainingHazards() {
        RefactorEvidenceGate gate = new RefactorEvidenceGate("性能优化：解决订单批量同步慢查询问题");
        assertTrue(gate.intent().performance());

        String beforeNPlusOne = """
            class SyncService {
                void sync(java.util.List<String> ids) {
                    for (String id : ids) {
                        orderRepo.findById(id);
                    }
                }
            }
            """;
        String fixedBatch = """
            class SyncService {
                void sync(java.util.List<String> ids) {
                    orderRepo.findAllByIds(ids);
                }
            }
            """;

        // 口头吹嘘“性能大幅提升、运行更快”但没有任何复杂度/次数/实测数据说明 -> 拦截
        String bluff = gate.evaluate(null, Set.of("src/main/SyncService.java"),
            Map.of("src/main/SyncService.java", new TextChange(beforeNPlusOne, fixedBatch)), true,
            "我已经优化得非常完美了，现在性能大幅提升，运行更快！");
        assertNotNull(bluff);
        assertTrue(bluff.contains("无数据支撑"), bluff);

        // 给出真实 N+1 批量收敛说明且通过测试 -> 放行
        String honestProof = gate.evaluate(null, Set.of("src/main/SyncService.java"),
            Map.of("src/main/SyncService.java", new TextChange(beforeNPlusOne, fixedBatch)), true,
            "已将循环内逐条查询 N+1 改为 findAllByIds 批量查询（DB 调用从 N 次降为 1 次），单测验证通过；本地无压测环境，未做毫秒级基准压测。");
        assertNull(honestProof);
    }

    @Test
    void consultationAndReadOnlyGoalsAreNeverFalselyNagged() {
        for (String consultGoal : List.of("评估一下这个老项目的重构方案", "看看怎么优化订单查询性能", "分析当前模块有哪些魔法值和边界情况", "给我一些提升鲁棒性的建议",
            "本轮只读：审查老项目重复代码，不要修改任何仓库文件",
            "Evaluate legacy code smells and suggest edge cases to test. Do not edit any files.")) {
            RefactorEvidenceGate gate = new RefactorEvidenceGate(consultGoal);
            assertFalse(gate.intent().active(), "Consultation/read-only goal must not activate gate: " + consultGoal);
            assertNull(gate.evaluate(null, Set.of(), Map.of(), false, "分析完毕，存在 3 处可优化点。"));
        }
    }
}
