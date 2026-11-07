package com.pilot.core.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StreamRepeatGuardTest {
    private static final String REPEATED = "**减少简历中的方案细节。**\n";

    @Test
    void flagsConsecutiveIdenticalLines() {
        StreamRepeatGuard guard = new StreamRepeatGuard();
        int hits = 0;
        for (int i = 0; i < 12; i++) {
            hits = Math.max(hits, guard.accept(REPEATED));
        }
        assertTrue(hits >= StreamRepeatGuard.IDENTICAL_LINES, "连续相同行必须判为复读");
    }

    @Test
    void keepsNormalListAndAlternatingLines() {
        StreamRepeatGuard guard = new StreamRepeatGuard();
        int hits = 0;
        for (int i = 0; i < 20; i++) {
            hits = Math.max(hits, guard.accept("- 第 " + i + " 条建议：把项目经验写具体\n"));
        }
        assertEquals(0, hits, "每条都不同的清单不是复读");
    }

    /** 模型退化常见形态：同一段被反复重写，每次只多几个字，行级完全相同抓不到。 */
    @Test
    void flagsRewrittenParagraphsThatGrowEachTime() {
        String base = "调整项目排序与篇幅。应研 Java 后端时，实习经历建议保持第一重点；Rover-Suite 的 Netty 代理与控制面"
            + "可以突出系统设计和性能，直播项目则压缩到最能证明分布式消息、缓存和压测能力的内容。若目标是业务后端，直播项目";
        String raw = base + "不。\n\n" + base + "不必。\n\n" + base + "不必展开。\n\n" + base + "不必展开五项。\n\n";
        StreamRepeatGuard guard = new StreamRepeatGuard();
        int hits = 0;
        for (int i = 0; i < raw.length(); i += 40) {
            hits = Math.max(hits, guard.accept(raw.substring(i, Math.min(raw.length(), i + 40))));
        }
        assertTrue(hits >= 4, "同一段被重写多遍、每次略长也要识别");
    }

    /** 复读的另一种形态：列表每行都是上一行的加长版，行不相等、开头也不足以按句比较。 */
    @Test
    void flagsListItemsThatGrowOneAfterAnother() {
        String[] lines = {"- **补充求职信息。** 可在", "- **补充求职信息。** 可在联系方式附近增加", "- **补充求职信息。** 可在联系方式附近增加求职",
            "- **补充求职信息。** 可在联系方式附近增加求职意向（Java", "- **补充求职信息。** 可在联系方式附近增加求职意向（Java 后端实习/校招）、工作",
            "- **补充求职信息。** 可在联系方式附近增加求职意向（Java 后端实习/校招）、工作地点或到",
            "- **补充求职信息。** 可在联系方式附近增加求职意向（Java 后端实习/校招）、工作地点或到岗时间"};
        String raw = String.join("\n", lines) + "\n";
        StreamRepeatGuard guard = new StreamRepeatGuard();
        int hits = 0;
        for (int i = 0; i < raw.length(); i += 12) {
            hits = Math.max(hits, guard.accept(raw.substring(i, Math.min(raw.length(), i + 12))));
        }
        assertTrue(hits >= 5, "列表项逐行加长也是复读");
    }

    @Test
    void keepsNormalChecklists() {
        String raw = "- 核对时间线\n- 精简技术技能\n- 让量化结果可复核\n- 区分开发验证与生产结果\n- 减少职责描述里的实现细节密度\n";
        StreamRepeatGuard guard = new StreamRepeatGuard();
        int hits = 0;
        for (int i = 0; i < raw.length(); i += 8) {
            hits = Math.max(hits, guard.accept(raw.substring(i, Math.min(raw.length(), i + 8))));
        }
        assertEquals(0, hits, "正常的清单每行内容不同，不是复读");
    }

    @Test
    void keepsProseThatStartsDifferently() {
        String[] paragraphs = {"建议先精简技能清单，把最相关的能力放在前面。", "技能清单不要堆砌名词，保留能被项目证明的部分。", "项目描述按问题、方案、结果三段来写，每段控制在两行。",
            "压测数据要补充测试口径，例如并发数、持续时间和机器规格。", "投递前核实链接和联系方式，避免过期信息。"};
        StreamRepeatGuard guard = new StreamRepeatGuard();
        int hits = 0;
        for (String paragraph : paragraphs) {
            hits = Math.max(hits, guard.accept(paragraph + "\n\n"));
        }
        assertEquals(0, hits, "同一主题但每句开头不同的正常段落不是复读");
    }

    @Test
    void keepsTablesAndCodeBlocks() {
        StreamRepeatGuard guard = new StreamRepeatGuard();
        int hits = 0;
        for (int i = 0; i < 10; i++) {
            hits = Math.max(hits, guard.accept("| 项目 " + i + " | Java | 后端 |\n| --- | --- | --- |\n"));
            hits = Math.max(hits, guard.accept("  return value" + i + ";\n"));
            hits = Math.max(hits, guard.accept("}\n"));
        }
        assertEquals(0, hits, "表格行与代码括号不应触发复读检测");
    }

    @Test
    void detectsRepeatDeliveredInSmallStreamingChunks() {
        StreamRepeatGuard guard = new StreamRepeatGuard();
        int hits = 0;
        String text = REPEATED.repeat(10);
        for (int i = 0; i < text.length(); i += 3) {
            hits = Math.max(hits, guard.accept(text.substring(i, Math.min(text.length(), i + 3))));
        }
        assertTrue(hits >= StreamRepeatGuard.IDENTICAL_LINES, "逐 token 到达时也要能识别");
    }
}
