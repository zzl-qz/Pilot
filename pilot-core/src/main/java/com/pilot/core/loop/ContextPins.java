package com.pilot.core.loop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.pilot.core.message.Message;
import com.pilot.core.tool.ToolOutputArchiveReference;
import com.pilot.core.tool.ToolResults;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 压缩元数据由程序维护；用户原文及证据引用不交给摘要模型重写。 */
final class ContextPins {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MARKER = "\n<pilot_context_metadata>";
    private static final String END = "</pilot_context_metadata>";
    private static final String HANDOFF_PREFIX = "[上下文交接摘要";
    private static final String DROPPED_PREFIX = "[更早的 ";
    private static final String REPOSITORY_SNAPSHOT_PREFIX = "## 仓库现状";
    private static final String TRUNCATED_GOAL_MARK = "\n…（超长目标已截断）\n";
    /** 旧版脏记录实测最多嵌套十几层；剥到上限即停，病态输入也不会耗尽栈。 */
    private static final int UNWRAP_DEPTH = 16;
    /** 字段内截断的头部占比：首尾都留，任务名和验收条件才都在。 */
    private static final int GOAL_HEAD_PERCENT = 65;
    private static final int MAX_ENVIRONMENT_CONSTRAINTS = 8;

    private ContextPins() {
    }

    static String capture(List<Message> messages, int end, String source) {
        LinkedHashSet<String> requests = new LinkedHashSet<>();
        LinkedHashSet<String> references = new LinkedHashSet<>();
        LinkedHashSet<String> constraints = new LinkedHashSet<>();
        for (Message message : messages.subList(1, end)) {
            String text = message.content();
            if (isHandoff(text)) {
                unwrap(text, requests, references, constraints, UNWRAP_DEPTH);
            } else if ("user".equals(message.role()) && !isInternal(text)) {
                requests.add(text);
            }
            // 只从原始工具消息取引用与环境受限记录；摘要 JSON 中已合并的不二次截取。
            if ("tool".equals(message.role())) {
                addReference(text, references);
                addEnvironmentConstraint(text, constraints);
            }
        }
        return rendered(1, source, end - 1, requests, references, constraints);
    }

    /**
     * 超长时先丢最旧的已读引用，再丢环境提示，再淘汰中间较早的请求（保住原始目标与最新用户指令/中途纠正），
     * 仍超限时先在最新指令字段内截断，最后才在首条原始目标字段内部截断。
     * 输出永远是闭合的元数据块且长度不超过 {@code max}，绝不在闭合标签外拼尾巴。
     */
    static String limit(String pins, int max) {
        if (pins == null || max <= 0) {
            return "";
        }
        JsonNode metadata = parsedMetadata(pins);
        if (metadata == null) {
            return emptyMetadata(max);
        }
        if (pins.endsWith(END) && pins.length() <= max) {
            return pins;
        }
        List<String> requests = new ArrayList<>();
        List<String> references = new ArrayList<>();
        List<String> constraints = new ArrayList<>();
        metadata.path("evidence_refs").forEach(item -> references.add(item.asText()));
        metadata.path("user_requests").forEach(item -> requests.add(item.asText()));
        if (metadata.path("environment_constraints").isArray()) {
            metadata.path("environment_constraints").forEach(item -> constraints.add(item.asText()));
        }
        while (!references.isEmpty() && rendered(metadata, requests, references, constraints).length() > max) {
            references.remove(0);
        }
        while (!constraints.isEmpty() && rendered(metadata, requests, references, constraints).length() > max) {
            constraints.remove(0);
        }
        while (requests.size() > 2 && rendered(metadata, requests, references, constraints).length() > max) {
            requests.remove(1);
        }
        if (requests.isEmpty()) {
            return emptyMetadata(max);
        }
        String fitted = rendered(metadata, requests, references, constraints);
        if (fitted.length() <= max) {
            return fitted;
        }
        if (requests.size() == 2) {
            String withLatest =
                truncateLatest(metadata, references, constraints, requests.get(0), requests.get(1), max);
            if (!withLatest.isEmpty()) {
                return withLatest;
            }
            requests.remove(1);
            fitted = rendered(metadata, requests, references, constraints);
            if (fitted.length() <= max) {
                return fitted;
            }
        }
        return truncateGoal(metadata, references, constraints, requests.get(0), max);
    }

    /** 同时保留原始目标与最新用户指令的头尾摘录；空间放不下两条时返回空串，回落到只保留原始目标。 */
    private static String truncateLatest(JsonNode metadata, List<String> references, List<String> constraints,
        String goal, String latest, int max) {
        int minKeep = Math.min(32, latest.length());
        if (minKeep <= 0
            || rendered(metadata, List.of(goal, excerpt(latest, minKeep)), references, constraints).length() > max) {
            return "";
        }
        int low = minKeep;
        int high = latest.length();
        String fitted = "";
        while (low <= high) {
            int keep = (low + high) >>> 1;
            String candidate = rendered(metadata, List.of(goal, excerpt(latest, keep)), references, constraints);
            if (candidate.length() <= max) {
                fitted = candidate;
                low = keep + 1;
            } else {
                high = keep - 1;
            }
        }
        return fitted;
    }

    private static String rendered(JsonNode metadata, List<String> requests, List<String> references,
        List<String> constraints) {
        return rendered(metadata.path("schema_version").asInt(1), metadata.path("source_sha256").asText(""),
            metadata.path("covered_messages").asInt(0), requests, references, constraints);
    }

    private static String rendered(int schemaVersion, String source, int coveredMessages, Iterable<String> requests,
        Iterable<String> references, Iterable<String> constraints) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", schemaVersion);
        payload.put("source_sha256", source);
        payload.put("covered_messages", coveredMessages);
        payload.put("user_requests", requests);
        payload.put("evidence_refs", references);
        if (constraints.iterator().hasNext()) {
            payload.put("environment_constraints", constraints);
        }
        return MARKER + JSON.writeValueAsString(payload) + END;
    }

    /** 单条目标自身就超限时只在字段内部截断，外层结构一个字都不动。 */
    private static String truncateGoal(JsonNode metadata, List<String> references, List<String> constraints,
        String goal, int max) {
        int low = 0;
        int high = goal.length();
        String fitted = "";
        while (low <= high) {
            int keep = (low + high) >>> 1;
            String candidate = rendered(metadata, List.of(excerpt(goal, keep)), references, constraints);
            if (candidate.length() <= max) {
                fitted = candidate;
                low = keep + 1;
            } else {
                high = keep - 1;
            }
        }
        return fitted.isEmpty() ? emptyMetadata(max) : fitted;
    }

    /** 首 65% + 截断标记 + 尾 35%：目标开头的任务名和结尾的验收条件都留下。 */
    private static String excerpt(String goal, int keep) {
        if (keep >= goal.length()) {
            return goal;
        }
        int head = keep * GOAL_HEAD_PERCENT / 100;
        int tailStart = goal.length() - (keep - head);
        // 别把代理对切成两半，否则 JSON 里会留下孤立代理字符。
        if (head > 0 && Character.isHighSurrogate(goal.charAt(head - 1))) {
            head--;
        }
        if (tailStart < goal.length() && Character.isLowSurrogate(goal.charAt(tailStart))) {
            tailStart++;
        }
        return goal.substring(0, head) + TRUNCATED_GOAL_MARK + goal.substring(tailStart);
    }

    /** 上限小到连空壳都放不下时只能给空串：宁可什么都不钉，也不产出缺闭合标签的残片。 */
    private static String emptyMetadata(int max) {
        String shell = MARKER + JSON.writeValueAsString(Map.of("schema_version", 1, "source_sha256", "",
            "covered_messages", 0, "user_requests", List.of(), "evidence_refs", List.of())) + END;
        return shell.length() <= max ? shell : "";
    }

    /**
     * 旧版会在闭合标签后拼尾巴，也会把整份旧摘要再嵌进 user_requests；
     * 这里只认 start/end 之间的 JSON，并按层剥掉嵌套，还原单层干净元数据。
     */
    private static JsonNode parsedMetadata(String text) {
        int start = text.lastIndexOf(MARKER);
        // 嵌套的旧块里，内层闭合标签会以字面量出现在字符串值中，只有最后一个才是本层结尾。
        int endTag = start < 0 ? -1 : text.lastIndexOf(END);
        if (start < 0 || endTag <= start) {
            return null;
        }
        try {
            JsonNode metadata = JSON.readTree(text.substring(start + MARKER.length(), endTag));
            return metadata.path("user_requests").isArray() && metadata.path("evidence_refs").isArray() ? metadata
                : null;
        } catch (RuntimeException ignored) {
            // 不是合法 JSON：交给调用方保留原文，绝不在这里重建半截元数据。
            return null;
        }
    }

    private static void unwrap(String text, LinkedHashSet<String> requests, LinkedHashSet<String> references,
        LinkedHashSet<String> constraints, int depth) {
        JsonNode metadata = parsedMetadata(text);
        if (metadata == null) {
            // 没有可解析的结构：这份唯一交接记录不能丢。
            if (!isInternal(text)) {
                requests.add(text);
            }
            return;
        }
        metadata.path("evidence_refs").forEach(item -> references.add(item.asText()));
        if (metadata.path("environment_constraints").isArray()) {
            metadata.path("environment_constraints").forEach(item -> addBoundedConstraint(item.asText(), constraints));
        }
        for (JsonNode item : metadata.path("user_requests")) {
            String request = item.asText();
            if (depth > 1 && request.contains(MARKER)) {
                unwrap(request, requests, references, constraints, depth - 1);
            } else if (!isInternal(request)) {
                requests.add(request);
            }
        }
    }

    private static boolean isHandoff(String text) {
        return text.startsWith(HANDOFF_PREFIX) || text.startsWith(DROPPED_PREFIX);
    }

    /** 框架以 user 角色注入的提醒、截图、模型切换提醒和临时系统提示都不是用户目标；仓库快照另外单独识别。 */
    private static boolean isInternal(String content) {
        return Message.isInternalUserText(content) || content.stripLeading().startsWith(REPOSITORY_SNAPSHOT_PREFIX);
    }

    private static void addReference(String text, LinkedHashSet<String> references) {
        String ref = ToolOutputArchiveReference.trailingReference(text);
        if (ref != null) {
            references.add(ref);
        }
    }

    /** 把外层 OS 沙箱导致的嵌套子沙箱受限命令钉入元数据，防止上下文压缩后重复运行同类必然卡死/失败的命令。 */
    private static void addEnvironmentConstraint(String text, LinkedHashSet<String> constraints) {
        if (!ToolResults.isNestedSandboxFailure(text)) {
            return;
        }
        String command = extractCommandLine(text);
        String entry = command.isEmpty() ? "外层 OS 沙箱禁止嵌套子沙箱（Electron/Chromium/bwrap/sandbox-exec），请勿重复运行同类受限命令"
            : "外层 OS 沙箱禁止嵌套子沙箱（已受阻命令：" + command + "），请勿重复运行同类命令";
        addBoundedConstraint(entry, constraints);
    }

    private static void addBoundedConstraint(String entry, LinkedHashSet<String> constraints) {
        if (entry == null || entry.isBlank()) {
            return;
        }
        if (constraints.size() >= MAX_ENVIRONMENT_CONSTRAINTS && !constraints.contains(entry)) {
            return;
        }
        constraints.add(entry);
    }

    private static String extractCommandLine(String text) {
        int outputIndex = text.indexOf("\n输出:\n");
        String header = outputIndex >= 0 ? text.substring(0, outputIndex) : text;
        int start = 0;
        while (start <= header.length()) {
            int end = header.indexOf('\n', start);
            String line = end < 0 ? header.substring(start) : header.substring(start, end);
            if (line.startsWith("命令: ")) {
                String cmd = line.substring("命令: ".length()).strip();
                return cmd.length() > 120 ? cmd.substring(0, 120) + "…" : cmd;
            }
            if (end < 0) {
                break;
            }
            start = end + 1;
        }
        return "";
    }
}
