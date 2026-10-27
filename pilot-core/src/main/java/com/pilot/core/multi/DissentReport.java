package com.pilot.core.multi;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 分歧汇总报告：同一任务多个子 Agent 并行出方案、结论不一致时的仲裁材料。
 *
 * <p>父 Agent 只做分歧梳理，不做裁判：它给出方案、证据、风险与核心分歧点，
 * 由人选择采用哪一方案、是否重新论证、或废弃任务。
 */
public record DissentReport(String taskId, String title, List<AgentReport> sides, String analysis) {

    public static final String OPTION_ACCEPT_A = "选用方案 A";
    public static final String OPTION_ACCEPT_B = "选用方案 B";
    public static final String OPTION_DEBATE = "发起新一轮辩论论证";
    public static final String OPTION_ABANDON = "废弃该任务";

    public DissentReport {
        taskId = taskId == null ? "" : taskId;
        title = title == null ? taskId : title;
        sides = sides == null ? List.of() : List.copyOf(sides);
        analysis = analysis == null ? "" : analysis;
    }

    /** 多个方案是否真的不一致：去掉空白与标点后比较，纯措辞差异不算分歧。 */
    public static boolean differs(List<AgentReport> sides) {
        if (sides == null || sides.size() < 2) {
            return false;
        }
        String first = normalize(sides.get(0).conclusion());
        for (int i = 1; i < sides.size(); i++) {
            if (!normalize(sides.get(i).conclusion()).equals(first)) {
                return true;
            }
        }
        return false;
    }

    public static List<String> options(int sideCount) {
        List<String> options = new ArrayList<>();
        for (int i = 0; i < Math.max(2, sideCount); i++) {
            options.add(switch (i) {
                case 0 -> OPTION_ACCEPT_A;
                case 1 -> OPTION_ACCEPT_B;
                default -> "选用方案 " + (char) ('A' + i);
            });
        }
        options.add(OPTION_DEBATE);
        options.add(OPTION_ABANDON);
        return List.copyOf(options);
    }

    /** 给用户与父 Agent 看的结构化报告（UI 直接按段落渲染）。 */
    public String toText() {
        StringBuilder out = new StringBuilder();
        out.append("## ⚠️ 分歧待仲裁：").append(title).append("（").append(taskId).append("）\n\n");
        char label = 'A';
        for (AgentReport side : sides) {
            out.append(side.describe("方案 " + label + " ｜ " + side.agentId()));
            out.append('\n');
            label++;
        }
        out.append("### 父 Agent 的分歧梳理\n");
        out.append(analysis.isEmpty() ? "- 各方案证据强度接近，无法在 LLM 层面判定优劣，需要人工裁决。\n" : analysis);
        out.append("\n### 可选操作\n");
        for (String option : options(sides.size())) {
            out.append("- ").append(option).append('\n');
        }
        out.append("\n> 说明：父 Agent 不会自动投票或挑选方案；在你做出选择之前，该任务及其下游不会继续。\n");
        return out.toString();
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。；：！？、（）【】《》]", "");
    }
}
