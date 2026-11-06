package com.pilot.core.loop;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;

/** 压缩元数据由程序维护；用户原文及证据引用不交给摘要模型重写。 */
final class ContextPins {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MARKER = "\n<pilot_context_metadata>";
    private static final String END = "</pilot_context_metadata>";

    private ContextPins() {
    }

    static String capture(List<Message> messages, int end, String source) {
        LinkedHashSet<String> requests = new LinkedHashSet<>();
        LinkedHashSet<String> references = new LinkedHashSet<>();
        for (Message message : messages.subList(1, end)) {
            String text = message.content();
            if (text.startsWith("[上下文交接摘要") || text.startsWith("[更早的 ")) {
                int start = text.lastIndexOf(MARKER);
                if (start >= 0 && text.endsWith(END)) {
                    try {
                        var metadata =
                            JSON.readTree(text.substring(start + MARKER.length(), text.length() - END.length()));
                        if (!metadata.path("user_requests").isArray() || !metadata.path("evidence_refs").isArray()) {
                            requests.add(text);
                        } else {
                            metadata.path("user_requests").forEach(item -> requests.add(item.asText()));
                            metadata.path("evidence_refs").forEach(item -> references.add(item.asText()));
                        }
                    } catch (RuntimeException invalidMetadata) {
                        requests.add(text);
                    }
                } else {
                    // 旧摘要没有结构化元数据，不能再把这份唯一交接记录扔掉。
                    requests.add(text);
                }
            } else if ("user".equals(message.role()) && !message.isToolImages() && !text.startsWith("## 仓库现状")
                && !text.startsWith("[系统")) {
                requests.add(text);
            }
            // 只从原始工具消息取引用；摘要 JSON 中已合并的引用不能再次当成原文截取。
            int ref = "tool".equals(message.role()) ? text.lastIndexOf("[工具输出存档 run_id=") : -1;
            int close = ref < 0 ? -1 : text.indexOf(']', ref);
            if (close >= 0) {
                references.add(text.substring(ref, close + 1));
            }
        }
        return MARKER + JSON.writeValueAsString(Map.of("schema_version", 1, "source_sha256", source, "covered_messages",
            end - 1, "user_requests", requests, "evidence_refs", references)) + END;
    }
}
