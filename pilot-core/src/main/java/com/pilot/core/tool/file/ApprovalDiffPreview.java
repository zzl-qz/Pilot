package com.pilot.core.tool.file;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pilot.core.tool.ToolSupport;

/**
 * 为文件编辑 / 创建 / 补丁工具生成紧凑的审批预览差异，避免审批卡片信息过载又让用户无需盲批。
 */
final class ApprovalDiffPreview {
    private static final int MAX_LINES = 36;
    private static final int MAX_CHARS = 1800;

    private ApprovalDiffPreview() {
    }

    static Map<String, Object> forEdit(Map<String, Object> args) {
        String path = ToolSupport.stringArg(args, "path");
        StringBuilder out = new StringBuilder();
        if (path != null) {
            out.append("--- a/").append(path).append('\n');
            out.append("+++ b/").append(path).append('\n');
        }
        try {
            List<ExactEditBatch.Edit> edits = ExactEditBatch.parse(args);
            for (int i = 0; i < edits.size(); i++) {
                ExactEditBatch.Edit edit = edits.get(i);
                out.append("@@ 替换 #").append(i + 1).append(" @@\n");
                appendPrefixed(out, "-", edit.oldText());
                appendPrefixed(out, "+", edit.newText());
            }
        } catch (IllegalArgumentException ignored) {
            // 参数不完整时不抛出异常，由工具执行阶段给出结构化错误
        }
        return result(path, out.toString());
    }

    static Map<String, Object> forCreate(Map<String, Object> args) {
        String path = ToolSupport.stringArg(args, "path");
        Object rawContent = args == null ? null : args.get("content");
        String content = rawContent == null ? "" : rawContent.toString();
        StringBuilder out = new StringBuilder();
        out.append("--- /dev/null\n");
        if (path != null) {
            out.append("+++ b/").append(path).append('\n');
        }
        out.append("@@ 新建文件 @@\n");
        appendPrefixed(out, "+", content);
        return result(path, out.toString());
    }

    static Map<String, Object> forPatch(Map<String, Object> args) {
        Object rawPatch = args == null ? null : args.get("patch");
        if (!(rawPatch instanceof String patch) || patch.isBlank()) {
            return Map.of();
        }
        return result(null, patch);
    }

    private static void appendPrefixed(StringBuilder out, String prefix, String text) {
        if (text == null) {
            return;
        }
        text.lines().forEach(line -> out.append(prefix).append(line).append('\n'));
        if (text.isEmpty()) {
            out.append(prefix).append('\n');
        }
    }

    private static Map<String, Object> result(String path, String rawDiff) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (path != null && !path.isBlank()) {
            map.put("path", path);
        }
        String capped = cap(rawDiff);
        if (!capped.isEmpty()) {
            map.put("previewDiff", capped);
        }
        return Map.copyOf(map);
    }

    static String cap(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        List<String> lines = text.lines().toList();
        StringBuilder sb = new StringBuilder();
        boolean truncated = false;
        int count = Math.min(lines.size(), MAX_LINES);
        for (int i = 0; i < count; i++) {
            String line = lines.get(i);
            if (sb.length() + line.length() + 1 > MAX_CHARS) {
                int remain = Math.max(0, MAX_CHARS - sb.length());
                if (remain > 0) {
                    sb.append(line, 0, Math.min(remain, line.length())).append('\n');
                }
                truncated = true;
                break;
            }
            sb.append(line).append('\n');
        }
        if (lines.size() > MAX_LINES || truncated) {
            sb.append("…（差异已截断，完整内容将在改动后查看）");
        }
        return sb.toString().stripTrailing();
    }
}
