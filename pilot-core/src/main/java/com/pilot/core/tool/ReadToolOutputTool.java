package com.pilot.core.tool;

import java.util.List;
import java.util.Map;

import com.pilot.core.message.ToolNames;

import lombok.RequiredArgsConstructor;

/** 会话作用域内分页恢复原始观察；永不重新执行工具。 */
@RequiredArgsConstructor
public final class ReadToolOutputTool implements Tool.ParallelReadOnly {
    private final ToolCallJournal journal;

    @Override
    public String name() {
        return ToolNames.READ_TOOL_OUTPUT;
    }

    @Override
    public String description() {
        return "按存档引用的 run_id、call_id 分页读取本会话原始工具输出，不重放命令或修改。offset 为字符偏移，limit 最大 16000。找不到时明确返回错误。";
    }

    @Override
    public Map<String, Object> parameters() {
        return ToolSchemas.object(Map.of("run_id", Map.of("type", "string"), "call_id", Map.of("type", "string"),
            "offset", Map.of("type", "integer", "minimum", 0), "limit",
            Map.of("type", "integer", "minimum", 1, "maximum", 16000)), List.of("run_id", "call_id"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String run = ToolSupport.stringArg(args, "run_id");
        String call = ToolSupport.stringArg(args, "call_id");
        if (run == null || call == null) {
            return "ERROR: 需要 run_id 和 call_id";
        }
        int offset = args.get("offset") instanceof Number n ? n.intValue() : 0;
        int limit = args.get("limit") instanceof Number n ? n.intValue() : 8000;
        if (offset < 0 || limit < 1 || limit > 16000) {
            return "ERROR: offset 必须非负，limit 必须在 1–16000";
        }
        var saved = journal.observation(run, call);
        if (saved.isEmpty()) {
            return "ERROR: 本会话没有该输出存档（可能未成功归档或已清理）；不要重放有副作用的工具。";
        }
        String value = saved.get();
        if (offset > value.length()) {
            return "ERROR: offset 超出输出长度 " + value.length();
        }
        int end = (int)Math.min(value.length(), (long)offset + limit);
        return "offset=" + offset + " next_offset=" + end + " total=" + value.length() + " eof="
            + (end == value.length()) + "\n" + value.substring(offset, end);
    }
}
