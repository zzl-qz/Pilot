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
import com.pilot.core.tool.PromptedTool;
import com.pilot.core.tool.ToolArtifacts;
import com.pilot.core.tool.ToolResult;
import com.pilot.core.tool.ToolSchemas;
import com.pilot.core.tool.ToolSupport;

import tools.jackson.databind.JsonNode;

/**
 * 调用某个 MCP 服务器的工具。文本内容原样返回；图片内容落盘后作为图片交给模型；
 * 服务器报告 {@code isError} 时以 ERROR 开头，方便执行层按失败处理。
 *
 * <p>只读放行依赖绑定当前进程的用户级本地策略。服务器的 {@code readOnlyHint} 默认不可信；
 * 该策略影响 Pilot 权限判定，不提供操作系统隔离。
 */
public final class McpCallTool extends PromptedTool {
    private static final int MAX_TEXT_CHARS = 30_000;
    static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    private static final int MAX_IMAGES = 8;

    private final McpManager manager;
    private final Path workspace;
    private final AtomicLong images = new AtomicLong();

    private record Checked(Map<String, Object> args, McpManager.ReadOnlyGrant grant) {
    }

    private final ThreadLocal<Checked> checked = new ThreadLocal<>();

    public McpCallTool(McpManager manager, Path workspace) {
        super("mcp_call");
        this.manager = manager;
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    @Override
    public Map<String, Object> parameters() {
        return ToolSchemas.object(
            Map.of("server", ToolSchemas.string("MCP 服务器名"), "tool", ToolSchemas.string("工具名（来自 mcp_tools）"),
                "arguments", Map.of("type", "object", "description", "工具参数，结构以 mcp_tools 返回的 schema 为准")),
            List.of("server", "tool"));
    }

    @Override
    public boolean readOnly(Map<String, Object> args) {
        checked.remove();
        String server = ToolSupport.stringArg(args, "server");
        String tool = ToolSupport.stringArg(args, "tool");
        if (server == null || tool == null) {
            return true;
        }
        McpManager.ReadOnlyGrant grant = manager.readOnlyGrant(workspace, server, tool);
        if (grant != null) {
            checked.set(new Checked(args, grant));
        }
        return grant != null;
    }

    @Override
    public boolean confirm(Map<String, Object> args) {
        String server = ToolSupport.stringArg(args, "server");
        String tool = ToolSupport.stringArg(args, "tool");
        return server != null && tool != null
            && manager.permissionPolicy(workspace, server).requireApprovalTools().contains(tool);
    }

    @Override
    public String approvalDescription(Map<String, Object> args) {
        // 明确审批是新的执行依据，不能沿用同一次调用先前的只读快照。
        checked.remove();
        return null;
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
        Checked inspected = checked.get();
        checked.remove();
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
            result = manager.call(workspace, server, tool, arguments,
                inspected != null && inspected.args() == args ? inspected.grant() : null);
        } catch (McpException e) {
            return ToolResult.text("ERROR: " + e.getMessage());
        }
        return render(server, result);
    }

    ToolResult render(String server, JsonNode result) {
        StringBuilder text = new StringBuilder();
        List<Attachment> attachments = new ArrayList<>();
        long[] imageBytes = {0};
        for (JsonNode block : result.path("content")) {
            switch (block.path("type").asString("")) {
                case "text" -> append(text, block.path("text").asString(""));
                case "image" -> {
                    String note = saveImage(server, block, attachments, imageBytes);
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

    private String saveImage(String server, JsonNode block, List<Attachment> attachments, long[] imageBytes) {
        if (attachments.size() >= MAX_IMAGES) {
            return "[图片数量超过上限（8），已忽略]";
        }
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
            String encoded = block.path("data").asString("");
            if (decodedSize(encoded) > MAX_IMAGE_BYTES - imageBytes[0]) {
                return "[图片超过单张或累计大小上限（5 MiB），已在解码前拒绝]";
            }
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
                return "[图片大小异常，已忽略]";
            }
            Path dir = ToolArtifacts.directory("mcp/" + server.replaceAll("[^A-Za-z0-9._-]", "_"));
            Path file =
                dir.resolve("image-" + System.currentTimeMillis() + "-" + images.incrementAndGet() + "." + extension);
            Files.write(file, bytes);
            imageBytes[0] += bytes.length;
            attachments.add(new Attachment(file.getFileName().toString(),
                mime.equals("image/jpg") ? "image/jpeg" : mime, file.toString()));
            return "[图片 " + file.getFileName() + "]";
        } catch (IllegalArgumentException | IOException e) {
            return "[图片无法保存：" + e.getMessage() + "]";
        }
    }

    static long decodedSize(String encoded) {
        int length = encoded.length();
        int padding = encoded.endsWith("==") ? 2 : encoded.endsWith("=") ? 1 : 0;
        return (long)(length / 4) * 3 + switch (length % 4) {
            case 2 -> 1;
            case 3 -> 2;
            default -> 0;
        } - padding;
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
