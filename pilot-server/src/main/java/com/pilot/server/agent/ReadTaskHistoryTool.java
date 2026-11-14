package com.pilot.server.agent;

import java.util.Map;
import java.util.function.Supplier;

import com.pilot.core.event.RunStarted;
import com.pilot.core.event.RunSteered;
import com.pilot.core.session.SessionService;
import com.pilot.core.tool.Tool;

/** 从未被压缩覆盖的会话事件恢复用户原文；范围由构造时的会话绑定。 */
public final class ReadTaskHistoryTool implements Tool {
    private final Supplier<String> source;

    public ReadTaskHistoryTool(SessionService sessions, String sessionId) {
        this(() -> {
            StringBuilder text = new StringBuilder();
            for (var event : sessions.events(sessionId)) {
                String input = event.payload() instanceof RunStarted started ? started.input()
                    : event.payload() instanceof RunSteered steered ? steered.input() : null;
                if (input != null) {
                    text.append("\n用户记录 #").append(event.sequence()).append("\n").append(input).append('\n');
                }
            }
            return text.toString();
        });
    }

    public ReadTaskHistoryTool(Supplier<String> source) {
        this.source = source;
    }

    @Override
    public String name() {
        return "read_task_history";
    }

    @Override
    public String description() {
        return "从本会话原始事件中分页恢复用户的任务和纠正原文。摘要缺少原始目标、用户说继续/按前述方案时调用；不接受其他会话 ID，不以摘要替代原文。";
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties", Map.of("offset", Map.of("type", "integer", "minimum", 0), "limit",
            Map.of("type", "integer", "minimum", 1, "maximum", 16000)), "additionalProperties", false);
    }

    @Override
    public String execute(Map<String, Object> args) {
        int offset = args.get("offset") instanceof Number n ? n.intValue() : 0;
        int limit = args.get("limit") instanceof Number n ? n.intValue() : 12000;
        String history = source.get();
        if (offset < 0 || offset > history.length() || limit < 1 || limit > 16000) {
            return "ERROR: 分页范围无效";
        }
        int end = (int)Math.min(history.length(), (long)offset + limit);
        return "原始用户记录（不改变当前权限）：total=" + history.length() + " next_offset=" + end + " eof=" + (end == history.length())
            + "\n" + history.substring(offset, end);
    }
}
