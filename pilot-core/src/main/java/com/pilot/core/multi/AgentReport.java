package com.pilot.core.multi;

import java.util.ArrayList;
import java.util.List;

/**
 * 子 Agent 的固定三段式回报：结论 / 证据清单 / 风险评估。
 *
 * <p>父 Agent 只认这个结构：没有证据的结论会被标记为弱证据，不参与「看起来更合理」的采信。
 *
 * @param evidenceMissing 是否明确缺少源码或工具证据
 */
public record AgentReport(String agentId, String taskId, String conclusion,
                          List<String> evidence, List<String> risks, boolean evidenceMissing) {
    private static final String CONCLUSION = "【结论】";
    private static final String EVIDENCE = "【证据清单】";
    private static final String RISK = "【风险评估】";
    private static final List<String> NO_EVIDENCE_MARKS =
            List.of("无证据支撑", "无证据", "推测", "猜测", "未验证");

    public AgentReport {
        agentId = agentId == null ? "" : agentId;
        taskId = taskId == null ? "" : taskId;
        conclusion = conclusion == null ? "" : conclusion.strip();
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        risks = risks == null ? List.of() : List.copyOf(risks);
    }

    /** 解析模型输出；缺少标记时整段作为结论，并按「无证据」处理。 */
    public static AgentReport parse(String agentId, String taskId, String text) {
        String body = text == null ? "" : text.strip();
        int conclusionAt = body.indexOf(CONCLUSION);
        int evidenceAt = body.indexOf(EVIDENCE);
        int riskAt = body.indexOf(RISK);
        if (conclusionAt < 0 && evidenceAt < 0 && riskAt < 0) {
            return new AgentReport(agentId, taskId, body, List.of(), List.of(), true);
        }

        int firstAt = firstIndex(conclusionAt, evidenceAt, riskAt);
        String preface = firstAt > 0 ? body.substring(0, firstAt).strip() : "";
        String conclusion = section(body, conclusionAt, evidenceAt, riskAt);
        List<String> evidence = lines(section(body, evidenceAt, conclusionAt, riskAt));
        List<String> risks = lines(section(body, riskAt, conclusionAt, evidenceAt));
        if (conclusion.isEmpty()) {
            conclusion = preface.isEmpty() ? body : preface;
        }
        boolean weak = evidence.isEmpty() || evidence.stream()
                .anyMatch(line -> NO_EVIDENCE_MARKS.stream().anyMatch(line::contains));
        return new AgentReport(agentId, taskId, conclusion, evidence, risks, weak);
    }

    /** 一行摘要：给父 Agent 汇总与界面展示。 */
    public String headline() {
        String oneLine = conclusion.replaceAll("\\s+", " ").strip();
        if (oneLine.length() > 160) {
            oneLine = oneLine.substring(0, 160) + "…";
        }
        return oneLine.isEmpty() ? "（没有结论）" : oneLine;
    }

    public String describe(String label) {
        StringBuilder out = new StringBuilder();
        out.append("### ").append(label).append('\n');
        out.append("- 结论：").append(headline()).append('\n');
        out.append("- 证据：").append(evidence.isEmpty() ? "（未提供）" : "").append('\n');
        for (String line : evidence) {
            out.append("  - ").append(line).append('\n');
        }
        out.append("- 风险：").append(risks.isEmpty() ? "（未声明）" : "").append('\n');
        for (String line : risks) {
            out.append("  - ").append(line).append('\n');
        }
        if (evidenceMissing) {
            out.append("- ⚠️ 证据不足：该结论缺少可核验依据，采信优先级低\n");
        }
        return out.toString();
    }

    private static int firstIndex(int... values) {
        int result = Integer.MAX_VALUE;
        for (int value : values) {
            if (value >= 0 && value < result) {
                result = value;
            }
        }
        return result == Integer.MAX_VALUE ? -1 : result;
    }

    private static String section(String body, int marker, int... others) {
        if (marker < 0) {
            return "";
        }
        String name = body.startsWith(CONCLUSION, marker) ? CONCLUSION
                : body.startsWith(EVIDENCE, marker) ? EVIDENCE : RISK;
        int start = marker + name.length();
        int end = body.length();
        for (int other : others) {
            if (other > marker && other < end) {
                end = other;
            }
        }
        return body.substring(start, end).strip();
    }

    private static List<String> lines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String cleaned = line.strip().replaceFirst("^[-*•]\\s*", "").replaceFirst("^\\d+[.)]\\s*", "");
            if (!cleaned.isEmpty()) {
                result.add(cleaned);
            }
        }
        return List.copyOf(result);
    }
}
