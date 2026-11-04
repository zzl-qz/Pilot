package com.pilot.core.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.pilot.core.loop.Attachment;
import com.pilot.core.tool.Availability;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolArtifacts;
import com.pilot.core.tool.ToolResult;
import com.pilot.core.tool.ToolSupport;

import tools.jackson.databind.JsonNode;

/**
 * 调用某个 MCP 服务器的工具。文本内容原样返回；图片内容落盘后作为图片交给模型；
 * 服务器报告 {@code isError} 时以 ERROR 开头，方便执行层按失败处理。
 *
 * <p>权限：服务器在工具的 annotations 里声明 {@code readOnlyHint} 才视为只读，其余一律审批——
 * 远程工具的副作用本地无从判断，缺省要保守。
 */
public final class McpCallTool implements Tool {
    private static final int MAX_TEXT_CHARS = 30_000;
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    private final McpManager manager;
    private final Path workspace;
    private final AtomicLong images = new AtomicLong();

    public McpCallTool(McpManager manager, Path workspace) {
        this.manager = manager;
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "mcp_call";
    }

    @Override
    public String description() {
        return promptDescription();
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties",
            Map.of("server", Map.of("type", "string", "description", "MCP 服务器名"), "tool",
                Map.of("type", "string", "description", "工具名（来自 mcp_tools）"), "arguments",
                Map.of("type", "object", "description", "工具参数，结构以 mcp_tools 返回的 schema 为准")),
            "required", List.of("server", "tool"), "additionalProperties", false);
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public boolean readOnly(Map<String, Object> args) {
        String server = ToolSupport.stringArg(args, "server");
        String tool = ToolSupport.stringArg(args, "tool");
        if (server == null || tool == null) {
            return true;
        } // 参数不全只会返回错误
        return manager.knownTools(workspace, server).stream()
            .anyMatch(info -> info.name().equals(tool) && info.readOnlyHint());
    }

    @Override
    public Availability availability() {
        return manager.hasServers(workspace) ? Availability.ready()
            : Availability.unavailable("当前工作区没有配置 MCP 服务器（.mcp.json）");
    }

    @Override
    public String execute(Map<String, Object> args) {
        return executeResult(args).text();
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult executeResult(Map<String, Object> args) {
        String server = ToolSupport.stringArg(args, "server");
        String tool = ToolSupport.stringArg(args, "tool");
        if (server == null) {
            return ToolResult.text("ERROR: 缺少参数 server");
        }
        if (tool == null) {
            return ToolResult.text("ERROR: 缺少参数 tool");
        }
        Object raw = args.get("arguments");
        if (raw != null && !(raw instanceof Map<?, ?>)) {
            return ToolResult.text("ERROR: arguments 必须是对象");
        }
        Map<String, Object> arguments = raw == null ? Map.of() : (Map<String, Object>)raw;
        JsonNode result;
        try {
            result = manager.call(workspace, server, tool, arguments);
        } catch (McpException e) {
            return ToolResult.text("ERROR: " + e.getMessage());
        }
        return render(server, result);
    }

    private ToolResult render(String server, JsonNode result) {
        StringBuilder text = new StringBuilder();
        List<Attachment> attachments = new ArrayList<>();
        for (JsonNode block : result.path("content")) {
            switch (block.path("type").asString("")) {
                case "text" -> append(text, block.path("text").asString(""));
                case "image" -> {
                    String note = saveImage(server, block, attachments);
                    if (note != null) {
                        append(text, note);
                    }
                }
                case "resource" -> {
                    JsonNode resource = block.path("resource");
                    append(text, resource.has("text") ? resource.path("text").asString("")
                        : "[资源 " + resource.path("uri").asString("") + "]");
                }
                case "resource_link" -> append(text, "[资源链接 " + block.path("uri").asString("") + "]");
                default -> append(text, "[不支持的内容类型 " + block.path("type").asString("?") + "]");
            }
        }
        if (text.isEmpty() && attachments.isEmpty() && result.has("structuredContent")) {
            text.append(result.path("structuredContent"));
        }
        if (text.isEmpty() && attachments.isEmpty()) {
            text.append("（工具没有返回内容）");
        }
        String body = text.length() > MAX_TEXT_CHARS
            ? text.substring(0, MAX_TEXT_CHARS) + "\n…（已截断，原文 " + text.length() + " 字符）" : text.toString();
        if (result.path("isError").asBoolean(false)) {
            body = "ERROR: " + body;
        }
        return attachments.isEmpty() ? ToolResult.text(body) : ToolResult.withImages(body, attachments);
    }

    private String saveImage(String server, JsonNode block, List<Attachment> attachments) {
        String mime = block.path("mimeType").asString("image/png").toLowerCase(Locale.ROOT);
        String extension = switch (mime) {
            case "image/png" -> "png";
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> null;
        };
        if (extension == null) {
            return "[不支持的图片类型 " + mime + "]";
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(block.path("data").asString(""));
            if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
                return "[图片大小异常，已忽略]";
            }
            Path dir = ToolArtifacts.directory("mcp/" + server.replaceAll("[^A-Za-z0-9._-]", "_"));
            Path file =
                dir.resolve("image-" + System.currentTimeMillis() + "-" + images.incrementAndGet() + "." + extension);
            Files.write(file, bytes);
            attachments.add(new Attachment(file.getFileName().toString(),
                mime.equals("image/jpg") ? "image/jpeg" : mime, file.toString()));
            return "[图片 " + file.getFileName() + "]";
        } catch (IllegalArgumentException | IOException e) {
            return "[图片无法保存：" + e.getMessage() + "]";
        }
    }

    private static void append(StringBuilder out, String piece) {
        if (piece == null || piece.isEmpty()) {
            return;
        }
        if (!out.isEmpty()) {
            out.append('\n');
        }
        out.append(piece);
    }
}
