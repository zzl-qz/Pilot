package com.pilot.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pilot.core.loop.ContextCompactor;
import com.pilot.core.loop.Message;
import com.pilot.core.tool.ToolNames;

import tools.jackson.databind.ObjectMapper;

/** 对实际请求做可复核的文本估算；图片采用保守容量估值，厂商隐藏开销不伪造为精确 token。 */
public record ContextComposition(long totalTokens, boolean estimated, int attachments, List<Part> parts) {
    public record Part(String category, String label, long tokens) {
    }

    public record Source(String category, String text) {
    }

    private static final Map<String, String> LABELS = labels();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Map<String, String> labels() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("system", "系统提示");
        map.put("tools", "工具定义");
        map.put("rules", "项目约定与记忆");
        map.put("skills", "技能");
        map.put("mcp", "MCP 工具");
        map.put("agents", "子代理定义");
        map.put("runtime", "任务状态");
        map.put("summary", "压缩摘要");
        map.put("conversation", "对话与工具结果");
        return Collections.unmodifiableMap(map);
    }

    public static ContextComposition estimate(List<Message> messages, List<ToolDefinition> tools,
        List<Source> sources) {
        Map<String, Long> totals = new LinkedHashMap<>();
        Map<String, String> calledTools = new HashMap<>();
        int attachments = 0;
        for (Message message : messages) {
            attachments += message.attachments().size();
            String remaining = message.content();
            if (message.role().equals("system") || message.role().equals("developer")) {
                for (Source source : sources == null ? List.<Source>of() : sources) {
                    if (source.text() == null || source.text().isEmpty()) {
                        continue;
                    }
                    int at = remaining.indexOf(source.text());
                    if (at >= 0) {
                        add(totals, source.category(), source.text());
                        remaining = remaining.substring(0, at) + remaining.substring(at + source.text().length());
                    }
                }
                add(totals, remaining.stripLeading().startsWith("[Runtime") ? "runtime" : "system", remaining);
            } else {
                String category = remaining.startsWith("[上下文交接摘要") ? "summary"
                    : remaining.startsWith("[已激活技能") || remaining.startsWith("（用户为本任务指定了技能 ")
                        || ToolNames.USE_SKILL.equals(calledTools.get(message.toolCallId())) ? "skills"
                            : "conversation";
                add(totals, category, remaining);
            }
            for (var call : message.toolCalls()) {
                calledTools.put(call.callId(), call.name());
                add(totals, "conversation", call.name() + JSON.writeValueAsString(CanonicalJson.ordered(call.args())));
            }
        }
        for (ToolDefinition tool : tools == null ? List.<ToolDefinition>of() : tools) {
            String category = tool.name().startsWith("mcp") ? "mcp"
                : List.of(ToolNames.SPAWN_AGENT, ToolNames.DISPATCH_AGENTS).contains(tool.name()) ? "agents" : "tools";
            add(totals, category,
                tool.name() + tool.description() + JSON.writeValueAsString(CanonicalJson.ordered(tool.parameters())));
        }
        if (attachments > 0) {
            totals.put("conversation", totals.getOrDefault("conversation", 0L) + attachments * 1_024L);
        }
        List<Part> result = new ArrayList<>();
        LABELS.forEach((key, label) -> {
            if (totals.getOrDefault(key, 0L) > 0) {
                result.add(new Part(key, label, totals.get(key)));
            }
        });
        long total = result.stream().mapToLong(Part::tokens).sum();
        return new ContextComposition(total, true, attachments, List.copyOf(result));
    }

    /** Estimate the complete visible request, including tools and provenance sources. */
    public static ContextComposition estimateRequest(List<Message> messages, List<ToolDefinition> tools,
        List<Source> sources) {
        return estimate(messages, tools, sources);
    }

    private static void add(Map<String, Long> totals, String category, String text) {
        totals.merge(LABELS.containsKey(category) ? category : "system", (long)ContextCompactor.estimateTokens(text),
            Long::sum);
    }
}
