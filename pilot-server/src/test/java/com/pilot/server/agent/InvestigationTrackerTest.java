package com.pilot.server.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pilot.core.loop.ToolExecutor;
import com.pilot.core.message.ToolCall;
import com.pilot.core.message.ToolNames;
import com.pilot.core.tool.code.CodeSymbolTool;

class InvestigationTrackerTest {

    @Test
    void productiveCrossModuleExplorationDoesNotTriggerFalseNudge() {
        InvestigationTracker tracker = new InvestigationTracker();
        for (int i = 1; i <= 12; i++) {
            String path = "module" + i + "/Service" + i + ".java";
            tracker.observe(new ToolCall("c" + i, ToolNames.READ_FILE, Map.of("path", path)), new ToolExecutor.Outcome(
                ToolNames.READ_FILE, "1 | class Service" + i + " {}\n… 显示第 1 到第 120 行，文件共 120 行 …", null, null));
            assertNull(tracker.nextNudge(false), "持续读取新文件不应在第 " + i + " 次误催");
        }
        assertNull(tracker.nextNudge(false));
    }

    @Test
    void detectsZeroGainWanderingWithBreakdownAndRespectsCooldown() {
        InvestigationTracker tracker = new InvestigationTracker();
        // 先有 2 次有效读取建立阶段基线
        tracker.observe(new ToolCall("r1", ToolNames.READ_FILE, Map.of("path", "A.java")),
            new ToolExecutor.Outcome(ToolNames.READ_FILE, "1 | a\n… 显示第 1 到第 100 行，文件共 200 行 …", null, null));
        tracker.observe(new ToolCall("r2", ToolNames.READ_FILE, Map.of("path", "B.java")),
            new ToolExecutor.Outcome(ToolNames.READ_FILE, "1 | b\n… 显示第 1 到第 100 行，文件共 200 行 …", null, null));
        assertNull(tracker.nextNudge(false));

        // 连续 4 次零增益：2 次空搜索 + 1 次去重命中 + 1 次仅命中已知文件
        tracker.observe(new ToolCall("s1", ToolNames.SEARCH_TEXT, Map.of("pattern", "missing1")),
            new ToolExecutor.Outcome(ToolNames.SEARCH_TEXT, "未找到匹配项: missing1", null, null));
        tracker.observe(new ToolCall("s2", ToolNames.SEARCH_TEXT, Map.of("pattern", "missing2")),
            new ToolExecutor.Outcome(ToolNames.SEARCH_TEXT, "未找到匹配项: missing2", null, null));
        tracker.observe(new ToolCall("d1", ToolNames.READ_FILE, Map.of("path", "A.java")),
            new ToolExecutor.Outcome(ToolNames.READ_FILE, "文件未变化，沿用刚才读过的 A.java", null, null));
        tracker.observe(new ToolCall("s3", ToolNames.SEARCH_TEXT, Map.of("pattern", "known")),
            new ToolExecutor.Outcome(ToolNames.SEARCH_TEXT, "A.java:10: known\nB.java:20: known", null, null));

        InvestigationTracker.Nudge nudge = tracker.nextNudge(false);
        assertNotNull(nudge, "连续 4 次零增益应触发低增益收敛提醒");
        assertTrue(nudge.lowGain());
        assertTrue(nudge.diagnostics().contains("空搜索 2 次"), nudge.diagnostics());
        assertTrue(nudge.diagnostics().contains("重复/重叠读取 1 次"), nudge.diagnostics());
        assertTrue(nudge.diagnostics().contains("仅命中已知位置 1 次"), nudge.diagnostics());
        assertTrue(nudge.summary().contains("已读完 A.java、B.java"), nudge.summary());
        assertTrue(nudge.action().contains("直接调用 apply_patch"), nudge.action());

        // 冷却期内不应立刻再次刷屏
        tracker.observe(new ToolCall("s4", ToolNames.SEARCH_TEXT, Map.of("pattern", "missing3")),
            new ToolExecutor.Outcome(ToolNames.SEARCH_TEXT, "未找到匹配项: missing3", null, null));
        assertNull(tracker.nextNudge(false), "冷却期内不应重复触发");
    }

    @Test
    void editOrNewVerificationResetsInvestigationWindow() {
        InvestigationTracker tracker = new InvestigationTracker();
        for (int i = 1; i <= 6; i++) {
            tracker.observe(new ToolCall("s" + i, ToolNames.SEARCH_TEXT, Map.of("pattern", "miss" + i)),
                new ToolExecutor.Outcome(ToolNames.SEARCH_TEXT, "未找到匹配项: miss" + i, null, null));
        }
        assertNotNull(tracker.nextNudge(false));

        // 发生文件写入后阶段重置
        tracker.observe(new ToolCall("e1", ToolNames.EDIT_FILE, Map.of("path", "A.java")), new ToolExecutor.Outcome(
            ToolNames.EDIT_FILE, "已修改 A.java", List.of("A.java"), List.of(), ToolExecutor.OutcomeStatus.SUCCESS));
        assertNull(tracker.nextNudge(false));

        // 首次复现测试失败也算有效推进并重置
        for (int i = 1; i <= 5; i++) {
            tracker.observe(new ToolCall("s2_" + i, ToolNames.SEARCH_TEXT, Map.of("pattern", "m" + i)),
                new ToolExecutor.Outcome(ToolNames.SEARCH_TEXT, "未找到匹配项: m" + i, null, null));
        }
        tracker.observe(new ToolCall("t1", ToolNames.RUN_COMMAND, Map.of("command", "mvn test")),
            new ToolExecutor.Outcome(ToolNames.RUN_COMMAND, "AssertionError at AppTest:15", List.of(),
                List.of("mvn test → exit 1"), ToolExecutor.OutcomeStatus.PERMANENT_FAILURE));
        assertNull(tracker.nextNudge(false));
    }

    @Test
    void broadExplorationNudgeOnlyFiresForImplementationNeverForResearch() {
        InvestigationTracker tracker = new InvestigationTracker();
        for (int i = 1; i <= 16; i++) {
            String path = "pkg/File" + i + ".java";
            tracker.observe(new ToolCall("r" + i, ToolNames.READ_FILE, Map.of("path", path)), new ToolExecutor.Outcome(
                ToolNames.READ_FILE, "1 | class File" + i + " {}\n… 显示第 1 到第 100 行，文件共 100 行 …", null, null));
        }

        // 纯调研/只读模式绝不催改代码
        assertNull(tracker.nextNudge(true), "纯调研模式跨多文件有效阅读不应触发催动手提醒");

        // 实现模式达到阈值（>= 14 次且跨 >= 8 个文件）时发出非阻断编辑检查点
        InvestigationTracker.Nudge broad = tracker.nextNudge(false);
        assertNotNull(broad);
        assertFalse(broad.lowGain());
        assertTrue(broad.summary().contains("16 个文件"), broad.summary());
        assertTrue(broad.summary().contains("已读完"), broad.summary());
        assertTrue(broad.action().contains("直接调用 apply_patch"), broad.action());
        assertNull(tracker.nextNudge(false), "下一轮未满步进不应重复提醒");
    }

    @Test
    void detectsSmallFileSetHotspotRereadSpinAndNamesHotspotFiles() {
        InvestigationTracker tracker = new InvestigationTracker();
        // 围绕 4 个核心文件反复分页阅读 16 次（不满足 10 个文件的广度条件，但属于典型核心文件反复重读空转）
        String[] files = {"loop/AgentLoop.java", "loop/ContextCompactor.java", "loop/CacheContextTest.java",
            "model/ModelProfile.java"};
        for (int i = 0; i < 16; i++) {
            String path = files[i % files.length];
            int start = (i / files.length) * 150 + 1;
            int end = start + 149;
            tracker.observe(new ToolCall("r" + i, ToolNames.READ_FILE, Map.of("path", path)), new ToolExecutor.Outcome(
                ToolNames.READ_FILE, start + " | code\n… 显示第 " + start + " 到第 " + end + " 行，文件共 1200 行 …", null, null));
        }

        // 纯调研模式仍保持静默，不催改代码
        assertNull(tracker.nextNudge(true));

        InvestigationTracker.Nudge nudge = tracker.nextNudge(false);
        assertNotNull(nudge, "少数核心文件被反复读取 4 次以上且零编辑时必须提醒收敛");
        assertFalse(nudge.lowGain());
        assertTrue(nudge.summary().contains("AgentLoop.java 4次"), nudge.summary());
        assertTrue(nudge.summary().contains("ContextCompactor.java 4次"), nudge.summary());
        assertTrue(nudge.action().contains("直接调用 apply_patch"), nudge.action());
    }

    @Test
    void researchModeLowGainNudgeNeverDemandsCodeEdits() {
        InvestigationTracker tracker = new InvestigationTracker();
        tracker.observe(new ToolCall("c1", CodeSymbolTool.NAME, Map.of("path", "A.java")),
            new ToolExecutor.Outcome(CodeSymbolTool.NAME, "A.java:1: class A", null, null));
        tracker.observe(new ToolCall("c2", CodeSymbolTool.NAME, Map.of("path", "B.java")),
            new ToolExecutor.Outcome(CodeSymbolTool.NAME, "B.java:1: class B", null, null));
        for (int i = 1; i <= 4; i++) {
            tracker.observe(new ToolCall("m" + i, ToolNames.SEARCH_TEXT, Map.of("pattern", "nope" + i)),
                new ToolExecutor.Outcome(ToolNames.SEARCH_TEXT, "未找到匹配项: nope" + i, null, null));
        }

        InvestigationTracker.Nudge nudge = tracker.nextNudge(true);
        assertNotNull(nudge);
        assertTrue(nudge.action().contains("收束结论"), nudge.action());
        assertFalse(nudge.action().contains("最小可执行改动"), nudge.action());
    }
}
