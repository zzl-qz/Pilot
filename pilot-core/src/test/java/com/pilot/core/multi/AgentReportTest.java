package com.pilot.core.multi;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentReportTest {

    @Test
    void parsesThreeSections() {
        String text = """
                【结论】
                应该在 PathValidator 里加白名单校验，而不是只在提示词里约束。

                【证据清单】
                - pilot-core/src/main/java/com/pilot/core/workspace/PathValidator.java:42 负责所有路径解析
                - pilot-core/src/main/java/com/pilot/core/tool/ListDirTool.java:60 直接列举目录

                【风险评估】
                - 白名单写法过宽会误放行
                """;

        AgentReport report = AgentReport.parse("agent_1", "T1", text);

        assertEquals("agent_1", report.agentId());
        assertTrue(report.conclusion().contains("PathValidator"));
        assertEquals(2, report.evidence().size());
        assertEquals(1, report.risks().size());
        assertFalse(report.evidenceMissing());
    }

    @Test
    void missingSectionsAreTreatedAsWeakEvidence() {
        AgentReport report = AgentReport.parse("agent_2", "T2", "我觉得应该这样改。");

        assertTrue(report.evidenceMissing());
        assertTrue(report.evidence().isEmpty());
    }

    @Test
    void explicitNoEvidenceIsFlagged() {
        String text = """
                【结论】
                可能是缓存问题。

                【证据清单】
                - 无证据支撑，推测

                【风险评估】
                - 未验证
                """;

        assertTrue(AgentReport.parse("agent_3", "T3", text).evidenceMissing());
    }

    @Test
    void dissentComparesConclusionsNotWording() {
        var a = new AgentReport("a", "T1", "用方案一：加白名单校验", List.of("A.java:10"), List.of(), false);
        var b = new AgentReport("b", "T1", "用方案一: 加白名单校验！", List.of("A.java:10"), List.of(), false);
        var c = new AgentReport("c", "T1", "用反射绕开校验", List.of("B.java:20"), List.of(), false);

        assertFalse(DissentReport.differs(List.of(a, b)));
        assertTrue(DissentReport.differs(List.of(a, c)));
    }

    @Test
    void dissentReportListsEveryOptionForHumanDecision() {
        var a = new AgentReport("agentA", "T1", "方案一", List.of("A.java:10"), List.of("风险一"), false);
        var b = new AgentReport("agentB", "T1", "方案二", List.of("B.java:20"), List.of(), true);
        var report = new DissentReport("T1", "改路径校验", List.of(a, b), "- 证据强度接近");

        String text = report.toText();

        assertTrue(text.contains("方案 A"));
        assertTrue(text.contains("方案 B"));
        assertTrue(text.contains(DissentReport.OPTION_DEBATE));
        assertTrue(text.contains("不会自动投票"));
    }
}
