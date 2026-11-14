package com.pilot.core.mcp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * MCP 服务器 args 输入的智能解析：同一输入框支持两种格式（对齐 zocde 的交互）。
 * <ul>
 *   <li>简写：空格分隔，双引号/单引号包裹内可含空格——{@code -y @scope/server "D:\my dir"}</li>
 *   <li>JSON：字符串数组——{@code ["-y", "@scope/server", "D:\\my dir"]}</li>
 * </ul>
 *
 * <p>解析在后端做而不是前端：写入 mcp.json 的结果与用户所见一致，前端不再各写一套拆分逻辑。
 * 解析失败抛带示例的 {@link McpException}，文案直接展示给用户。
 */
public final class McpArgsParser {
    private McpArgsParser() {
    }

    /**
     * @param raw 用户输入；{@code null} 或空白视为无参数
     * @return 参数列表
     */
    public static List<String> parse(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.isEmpty()) {
            return List.of();
        }
        // JSON 数组（或误入的 JSON 对象）走严格校验；否则按 shell 简写拆分
        if (text.startsWith("[") || text.startsWith("{")) {
            return parseJson(text);
        }
        return shellSplit(text);
    }

    /** JSON 数组格式：每个元素必须是字符串；误将对象当参数时给出改写引导。 */
    private static List<String> parseJson(String text) {
        JsonNode root;
        try {
            root = McpJson.parse(text.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new McpException(
                "args 看起来是 JSON 数组但解析失败（" + e.getMessage() + "）；" + "示例：[\"-y\", \"@scope/server\"]，或改用空格分隔的简写。");
        }
        if (!root.isArray()) {
            throw new McpException("args 的 JSON 格式必须是字符串数组；示例：[\"-y\", \"@scope/server\"]，" + "或改用空格分隔的简写。");
        }
        List<String> args = new ArrayList<>();
        for (JsonNode item : root) {
            if (!item.isString()) {
                throw new McpException("args 的 JSON 数组里每个元素都必须是字符串；" + "示例：[\"-y\", \"@scope/server\"]");
            }
            args.add(item.asString());
        }
        return List.copyOf(args);
    }

    /** 简写格式：空白分隔；双引号/单引号包裹作为整体，引号内可用 \\ 转义同款引号或反斜杠。 */
    private static List<String> shellSplit(String text) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean hasToken = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                // 只把 \" 与 \\ 当作转义；Windows 路径里的普通反斜杠原样保留
                if (c == '\\' && i + 1 < text.length() && (text.charAt(i + 1) == quote || text.charAt(i + 1) == '\\')) {
                    current.append(text.charAt(++i));
                } else if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                hasToken = true;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (hasToken) {
                    args.add(current.toString());
                    current.setLength(0);
                    hasToken = false;
                }
                continue;
            }
            current.append(c);
            hasToken = true;
        }
        if (quote != 0) {
            throw new McpException("args 里的引号没有成对；包含空格的参数用引号包住，" + "示例：-y @scope/server \"D:\\my dir\"，或改用 JSON 数组格式。");
        }
        if (hasToken) {
            args.add(current.toString());
        }
        return List.copyOf(args);
    }
}
