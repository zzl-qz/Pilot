package com.pilot.server.attachment;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.pilot.core.message.Attachment;
import com.pilot.core.util.FileTrees;
import com.pilot.server.common.data.PilotDataDir;
import com.pilot.server.common.data.StorageHealth;

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
    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/webp", "image/gif");
    private static final Set<String> TEXT_TYPES =
        Set.of("application/json", "application/xml", "application/yaml", "application/x-yaml",
            "application/javascript", "application/typescript", "application/sql", "application/x-sh");
    private static final Set<String> CODE_SUFFIXES = Set.of(".java", ".kt", ".kts", ".md", ".txt", ".json", ".xml",
        ".yml", ".yaml", ".properties", ".gradle", ".sql", ".js", ".mjs", ".ts", ".tsx", ".jsx", ".css", ".html",
        ".vue", ".svelte", ".py", ".go", ".rs", ".c", ".h", ".cpp", ".hpp", ".cs", ".rb", ".php", ".sh", ".bat", ".ps1",
        ".toml", ".ini", ".cfg", ".conf", ".log", ".diff", ".patch", ".pom");

    private final AttachmentLimits limits;
    private final Path root;

    @Autowired
    public AttachmentService(@Value("${pilot.attachments.dir:}") String configured, PilotDataDir dataDir,
        AttachmentLimits limits) {
        this(configured == null || configured.isBlank() ? dataDir.file("attachments") : Path.of(configured), limits);
    }

    AttachmentService(Path root) {
        this(root, AttachmentLimits.defaults());
    }

    AttachmentService(Path root, AttachmentLimits limits) {
        this.root = root.toAbsolutePath().normalize();
        this.limits = limits;
    }

    private record PendingAttachment(String name, String mime, byte[] bytes, boolean inlineText) {
    }

    /** 客户端提交的原始附件：文件名、MIME 与 base64 内容（可带 data URL 前缀）。 */
    public record Inbound(String name, String mimeType, String dataBase64) {
    }

    /** 处理好之后的结果：内联了文本的正文 + 需要模型侧编码的附件。 */
    public record Prepared(String content, List<Attachment> attachments, List<Integer> imagePositions) {
        public Prepared(String content, List<Attachment> attachments) {
            this(content, attachments, List.of());
        }
    }

    public Prepared prepare(String sessionId, String content, List<Inbound> inbound, List<Integer> positions,
        boolean mobile) {
        return mobile ? new AttachmentService(root, limits.mobile()).prepare(sessionId, content, inbound, positions)
            : prepare(sessionId, content, inbound, positions);
    }

    public Prepared prepare(String sessionId, String content, List<Inbound> inbound) {
        return prepare(sessionId, content, inbound, List.of());
    }

    public Prepared prepare(String sessionId, String content, List<Inbound> inbound, List<Integer> positions) {
        String body = content == null ? "" : content;
        if (inbound == null || inbound.isEmpty()) {
            return new Prepared(body, List.of(), List.of());
        }
        if (inbound.size() > limits.maxFiles()) {
            throw new IllegalArgumentException("一次最多附加 " + limits.maxFiles() + " 个文件");
        }
        List<Integer> imagePositions = positions == null ? List.of() : List.copyOf(positions);
        long imageCount = inbound.stream()
            .filter(item -> item != null && IMAGE_TYPES.contains(resolveMime(item.mimeType(), item.name()))).count();
        if (!imagePositions.isEmpty() && imagePositions.size() != imageCount) {
            throw new IllegalArgumentException("图片位置数量必须与图片附件数量一致");
        }

        StringBuilder text = new StringBuilder(body);
        ArrayList<Attachment> attachments = new ArrayList<Attachment>();
        ArrayList<PendingAttachment> pending = new ArrayList<PendingAttachment>();
        List<EncodedAttachment> encoded = preflight(inbound);
        for (EncodedAttachment encodedItem : encoded) {
            Inbound item = encodedItem.item();
            byte[] bytes = Base64.getDecoder().decode(encodedItem.raw());
            String mime = resolveMime(item.mimeType(), item.name());
            pending.add(new PendingAttachment(item.name(), mime, bytes, isInlineText(bytes, mime, item.name())));
        }
        if (text.toString().isBlank() && pending.isEmpty()) {
            throw new IllegalArgumentException("消息正文和附件不能同时为空");
        }
        ArrayList<Path> savedPaths = new ArrayList<Path>();
        try {
            for (PendingAttachment item : pending) {
                Attachment saved = save(sessionId, item.name(), item.mime(), item.bytes());
                savedPaths.add(Path.of(saved.path()));
                if (IMAGE_TYPES.contains(item.mime())) {
                    attachments.add(saved);
                } else if (item.inlineText()) {
                    text.append("\n\n").append(inline(item.name(), item.mime(), item.bytes(), saved.path()));
                    text.append("\n\n[附件 ").append(item.name()).append(" (").append(item.mime()).append(") 已保存到 ")
                        .append(saved.path()).append("，可用 read_attachment 读取]");
                } else {
                    text.append("\n\n[附件 ").append(item.name()).append(" (").append(item.mime()).append(") 已保存到 ")
                        .append(saved.path()).append("，可用 read_attachment 读取]");
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
        if (text.toString().isBlank() && imagePositions.isEmpty()) {
            text.append("请看随消息附上的文件：")
                .append(attachments.stream().map(Attachment::name).collect(Collectors.joining("、")));
        }
        return new Prepared(text.toString(), List.copyOf(attachments), imagePositions);
    }

    private record EncodedAttachment(Inbound item, String raw) {
    }

    /** 整批预检通过后才分配解码字节；长度包括 data URL 和空白，不能靠前缀绕过。 */
    private List<EncodedAttachment> preflight(List<Inbound> inbound) {
        List<EncodedAttachment> encoded = new ArrayList<>();
        long total = 0;
        for (Inbound item : inbound) {
            if (item == null || item.dataBase64() == null) {
                throw new IllegalArgumentException("附件缺少文件内容");
            }
            String raw = item.dataBase64();
            if (raw.length() > limits.maxEncodedFileChars()) {
                throw new IllegalArgumentException("附件「" + item.name() + "」超过单文件编码长度预算");
            }
            raw = raw.strip();
            if (raw.isEmpty()) {
                throw new IllegalArgumentException("附件「" + item.name() + "」内容为空");
            }
            if (raw.startsWith("data:")) {
                int comma = raw.indexOf(',');
                if (comma < 0 || !raw.substring(0, comma).endsWith(";base64")) {
                    throw new IllegalArgumentException("附件「" + item.name() + "」不是合法的 base64 data URL");
                }
                raw = raw.substring(comma + 1);
            }
            raw = raw.replaceAll("[ \t\r\n]", "");
            int padding = raw.endsWith("==") ? 2 : raw.endsWith("=") ? 1 : 0;
            int symbols = raw.length() - padding;
            if (symbols % 4 == 1 || (padding > 0 && raw.length() % 4 != 0)) {
                throw new IllegalArgumentException("附件「" + item.name() + "」不是合法的 base64");
            }
            for (int index = 0; index < symbols; index++) {
                char ch = raw.charAt(index);
                if (!(ch >= 'A' && ch <= 'Z' || ch >= 'a' && ch <= 'z' || ch >= '0' && ch <= '9' || ch == '+'
                    || ch == '/')) {
                    throw new IllegalArgumentException("附件「" + item.name() + "」不是合法的 base64");
                }
            }
            long bytes = symbols * 6L / 8L;
            if (bytes == 0 || bytes > limits.maxFileBytes()) {
                throw new IllegalArgumentException(
                    "附件「" + item.name() + "」需大于 0 且不超过 " + limits.maxFileBytes() + " 字节");
            }
            total += bytes;
            if (total > limits.maxTotalBytes()) {
                throw new IllegalArgumentException("附件总大小超过 " + limits.maxTotalBytes() + " 字节");
            }
            encoded.add(new EncodedAttachment(item, raw));
        }
        return encoded;
    }

    public record DisplayAttachment(String name, String mimeType, String url) {
    }

    public List<DisplayAttachment> displayAttachments(String sessionId, List<Attachment> attachments) {
        if (attachments == null) {
            return List.of();
        }
        return attachments.stream().filter(Attachment::image)
            .map(item -> new DisplayAttachment(item.name(), item.mimeType(),
                "/api/sessions/" + storageDirectory(sessionId) + "/attachments/" + storedFilename(item.path())))
            .toList();
    }

    public Path resolveImage(String sessionId, String fileName) {
        Path file = resolveAttachment(sessionId, fileName);
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif")
            || lower.endsWith(".webp"))) {
            throw new IllegalArgumentException("attachment is not an image");
        }
        return file;
    }

    public Path resolveAttachment(String sessionId, String fileName) {
        if (sessionId == null || fileName == null || !fileName.matches("[a-fA-F0-9-]{36}-.+")) {
            throw new IllegalArgumentException("invalid attachment reference");
        }
        Path sessionDir = root.resolve(safeName(sessionId, 80)).normalize();
        Path file = sessionDir.resolve(fileName).normalize();
        if (!file.getParent().equals(sessionDir) || !Files.isDirectory(sessionDir, LinkOption.NOFOLLOW_LINKS)
            || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("attachment not found");
        }
        return file;
    }

    /** 会话的附件目录；可能尚不存在（该会话还没有上传过附件）。 */
    public Path sessionDir(String sessionId) {
        return root.resolve(storageDirectory(sessionId));
    }

    public static String storageDirectory(String sessionId) {
        return safeName(sessionId, 80);
    }

    public static String storedFilename(String path) {
        return path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
    }

    public Path root() {
        return root;
    }

    /** 附件清理结果：删除的文件数与目录数。 */
    public record PurgeStats(int files, int dirs) {
    }

    /** 删除会话的整个附件目录；清理失败只记日志，不阻断会话删除。 */
    public void deleteBySession(String sessionId) {
        deleteTree(sessionDir(sessionId));
    }

    /**
     * 按保留策略清理附件：只删除不再属于在册会话的孤儿目录。
     * 在册会话的历史消息可能仍引用旧附件，因此不能仅凭修改时间删除。
     */
    public PurgeStats purge(Collection<String> liveSessionIds) {
        return purge(Instant.MAX, liveSessionIds);
    }

    public PurgeStats purge(Instant cutoff, Collection<String> liveSessionIds) {
        Set<String> live = liveSessionIds == null ? Set.of()
            : liveSessionIds.stream().map(id -> safeName(id, 80)).collect(Collectors.toSet());
        if (!Files.isDirectory(root)) {
            return new PurgeStats(0, 0);
        }
        int files = 0;
        int dirs = 0;
        try (Stream<Path> entries = Files.list(root)) {
            for (Path sessionDir : entries.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                .toList()) {
                String dirName = sessionDir.getFileName().toString();
                if (!live.contains(dirName) && Files.getLastModifiedTime(sessionDir).toInstant().isBefore(cutoff)) {
                    FileTrees.Deletion removed = deleteTree(sessionDir);
                    files += removed.files();
                    dirs += removed.directories();
                }
            }
        } catch (IOException e) {
            log.warn("遍历附件目录失败: {}", root, e);
            StorageHealth.markDegraded("attachment-purge", e.getMessage());
        }
        return new PurgeStats(files, dirs);
    }

    private static FileTrees.Deletion deleteTree(Path dir) {
        if (!Files.isDirectory(dir)) {
            return new FileTrees.Deletion(0, 0);
        }
        return FileTrees.deleteBestEffort(dir, (path, error) -> log.warn("清理附件 {} 失败", path, error));
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

    /**
     * 文本附件的内联片段：不超过预算时完整内联；超过时保留头尾（约 70%/30%）、
     * 省略中段，并在省略标记里写明全文长度与原件路径，模型可随时用工具读取全文。
     */
    private String inline(String name, String mime, byte[] bytes, String savedPath) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        int inlineMaxChars = limits.inlineMaxChars();
        if (text.length() <= inlineMaxChars) {
            return "[附件 " + name + " (" + mime + ")]\n" + text + "\n[附件结束 " + name + "]";
        }
        int headChars = Math.max(1, (int)(inlineMaxChars * 0.7));
        int tailChars = Math.max(1, inlineMaxChars - headChars);
        String head = text.substring(0, headChars);
        String tail = text.substring(text.length() - tailChars);
        return "[附件 " + name + " (" + mime + ")]\n" + head + "\n…（正文过长：共 " + text.length() + " 字符，保留头尾、省略中段。原文件已保存到 "
            + savedPath + "，可用 read_attachment 读取全文）\n" + tail + "\n[附件结束 " + name + "]";
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
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
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
