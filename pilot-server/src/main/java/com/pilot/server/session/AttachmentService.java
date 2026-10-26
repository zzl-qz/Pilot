package com.pilot.server.session;

import com.pilot.core.loop.Attachment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 把用户随消息粘贴的文件变成模型能看懂的上下文。
 *
 * <p>不按类型拒绝任何文件，按内容分三条路：
 * <ul>
 *   <li>能当文本读的（代码 / 配置 / 日志 / 未知但可解码 UTF-8）直接内联进正文，任何模型都能看；</li>
 *   <li>常见图片落盘后以 {@link Attachment} 挂到消息上，由模型客户端编码成 data URL；</li>
 *   <li>其余二进制（PDF / Office / 压缩包…）落盘，正文里给路径，让模型需要时用工具读取。</li>
 * </ul>
 *
 * <p>落盘位置默认 {@code ~/.pilot/attachments/{sessionId}/}，避免把 base64 塞进对话记忆。
 */
@Component
public class AttachmentService {
    static final int MAX_FILES = 5;
    static final int MAX_BYTES = 2 * 1024 * 1024;
    static final int MAX_INLINE_CHARS = 200_000;

    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/webp", "image/gif");
    private static final Set<String> TEXT_TYPES = Set.of(
            "application/json", "application/xml", "application/yaml", "application/x-yaml",
            "application/javascript", "application/typescript", "application/sql", "application/x-sh");
    private static final Set<String> CODE_SUFFIXES = Set.of(
            ".java", ".kt", ".kts", ".md", ".txt", ".json", ".xml", ".yml", ".yaml", ".properties",
            ".gradle", ".sql", ".js", ".mjs", ".ts", ".tsx", ".jsx", ".css", ".html", ".vue", ".svelte",
            ".py", ".go", ".rs", ".c", ".h", ".cpp", ".hpp", ".cs", ".rb", ".php", ".sh", ".bat",
            ".ps1", ".toml", ".ini", ".cfg", ".conf", ".log", ".diff", ".patch", ".pom");

    private final Path root;

    @Autowired
    public AttachmentService(@Value("${pilot.attachments.dir:}") String configured) {
        this(configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home", "."), ".pilot", "attachments")
                : Path.of(configured));
    }

    AttachmentService(Path root) {
        this.root = root;
    }

    private record PendingAttachment(String name, String mime, byte[] bytes) { }

    /** 客户端提交的原始附件：文件名、MIME 与 base64 内容（可带 data URL 前缀）。 */
    public record Inbound(String name, String mimeType, String dataBase64) {
    }

    /** 处理好之后的结果：内联了文本的正文 + 需要模型侧编码的附件。 */
    public record Prepared(String content, List<Attachment> attachments) {
    }

    public Prepared prepare(String sessionId, String content, List<Inbound> inbound) {
        String body = content == null ? "" : content;
        if (inbound == null || inbound.isEmpty()) {
            return new Prepared(body, List.of());
        }
        if (inbound.size() > MAX_FILES) {
            throw new IllegalArgumentException("一次最多附加 " + MAX_FILES + " 个文件");
        }

        var text = new StringBuilder(body);
        var attachments = new ArrayList<Attachment>();
        var pending = new ArrayList<PendingAttachment>();
        for (Inbound item : inbound) {
            if (item == null || item.dataBase64() == null || item.dataBase64().isBlank()) {
                continue;
            }
            byte[] bytes = decode(item);
            String mime = resolveMime(item.mimeType(), item.name());
            if (isInlineText(bytes, mime, item.name())) {
                text.append("\n\n").append(inline(item.name(), mime, bytes));
            } else {
                pending.add(new PendingAttachment(item.name(), mime, bytes));
            }
        }
        if (text.toString().isBlank() && pending.stream().noneMatch(item -> IMAGE_TYPES.contains(item.mime()))) {
            throw new IllegalArgumentException("消息正文和附件不能同时为空");
        }
        var savedPaths = new ArrayList<Path>();
        try {
            for (PendingAttachment item : pending) {
                Attachment saved = save(sessionId, item.name(), item.mime(), item.bytes());
                savedPaths.add(Path.of(saved.path()));
                if (IMAGE_TYPES.contains(item.mime())) {
                    attachments.add(saved);
                } else {
                    text.append("\n\n[附件 ").append(item.name()).append(" (").append(item.mime())
                            .append(") 已保存到 ").append(saved.path())
                            .append("，需要时用工具读取]");
                }
            }
        } catch (RuntimeException e) {
            for (Path path : savedPaths) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException cleanupError) {
                    e.addSuppressed(cleanupError);
                }
            }
            throw e;
        }
        if (text.toString().isBlank()) {
            text.append("请看随消息附上的文件：")
                    .append(attachments.stream().map(Attachment::name).collect(Collectors.joining("、")));
        }
        return new Prepared(text.toString(), List.copyOf(attachments));
    }

    private byte[] decode(Inbound item) {
        String raw = item.dataBase64().strip();
        int comma = raw.indexOf(',');
        if (raw.startsWith("data:") && comma > 0) {
            raw = raw.substring(comma + 1);
        }
        byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("附件「" + item.name() + "」不是合法的 base64");
        }
        if (bytes.length == 0) {
            throw new IllegalArgumentException("附件「" + item.name() + "」内容为空");
        }
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("附件「" + item.name() + "」超过 " + (MAX_BYTES / 1024 / 1024) + " MB");
        }
        return bytes;
    }

    private Attachment save(String sessionId, String name, String mime, byte[] bytes) {
        try {
            Path dir = root.resolve(safeName(sessionId, 80));
            Files.createDirectories(dir);
            Path target = dir.resolve(UUID.randomUUID() + "-" + safeName(name, 60));
            Files.write(target, bytes);
            return new Attachment(name, mime, target.toAbsolutePath().toString());
        } catch (IOException e) {
            throw new IllegalStateException("保存附件失败：" + name, e);
        }
    }

    private static String inline(String name, String mime, byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        boolean truncated = text.length() > MAX_INLINE_CHARS;
        String body = truncated ? text.substring(0, MAX_INLINE_CHARS) : text;
        return "[附件 " + name + " (" + mime + ")]\n"
                + body
                + (truncated ? "\n…（附件过长已截断，原文件 " + text.length() + " 字符）" : "")
                + "\n[附件结束 " + name + "]";
    }

    private static String resolveMime(String mime, String name) {
        if (mime != null && !mime.isBlank() && !"application/octet-stream".equalsIgnoreCase(mime.strip())) {
            return mime.strip().toLowerCase(Locale.ROOT);
        }
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        if (lower.endsWith(".md")) {
            return "text/markdown";
        }
        return CODE_SUFFIXES.stream().anyMatch(lower::endsWith) ? "text/plain" : "application/octet-stream";
    }

    /**
     * 判断能否安全地当文本内联：先看声明的类型和扩展名，未知类型再按内容嗅探。
     * 嗅探失败（含 NUL、非法 UTF-8、控制字符过多）就当二进制处理。
     */
    private static boolean isInlineText(byte[] bytes, String mime, String name) {
        // 显式声明的图片一律按二进制走多模态；SVG 例外，它本身就是文本
        if ("image/svg+xml".equals(mime)) {
            return true;
        }
        if (mime.startsWith("image/")) {
            return false;
        }
        if (mime.startsWith("text/") || TEXT_TYPES.contains(mime)) {
            return true;
        }
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (CODE_SUFFIXES.stream().anyMatch(lower::endsWith)) {
            return true;
        }
        return looksLikeText(bytes);
    }

    private static boolean looksLikeText(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) {
                return false;
            }
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            long control = text.chars().filter(ch -> ch < 0x09 || (ch > 0x0D && ch < 0x20)).count();
            return control * 10 <= Math.max(1, text.length());
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static String safeName(String value, int limit) {
        String sanitized = (value == null ? "file" : value).replaceAll("[^A-Za-z0-9._-]", "_");
        if (sanitized.isBlank()) {
            sanitized = "file";
        }
        return sanitized.length() <= limit ? sanitized : sanitized.substring(0, limit);
    }
}
