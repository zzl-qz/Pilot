package com.pilot.server.work;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import com.pilot.core.loop.Message;
import com.pilot.core.session.ConversationRepository;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.core.workspace.PathValidator;
import com.pilot.server.config.PilotDataDir;
import com.pilot.server.store.AutomationStore;
import com.pilot.server.workspace.WorkspaceToolFactory;

import tools.jackson.databind.ObjectMapper;

/** 会话成果的不可变快照；来源阅读记录与结论正确性分开表示。 */
@Service
public class WorkProductService {
    private static final String KIND = "work-product";
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int PREVIEW_CHARS = 60_000;
    private static final Map<String, String> TYPES =
        Map.of("md", "text/markdown", "txt", "text/plain", "csv", "text/csv", "json", "application/json", "pdf",
            "application/pdf", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "pptx",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation");

    public record Source(String url, boolean observedRead, String callId) {
    }

    public record Product(String id, String sessionId, String runId, String title, String summary, String filename,
        String sourcePath, String mediaType, long bytes, String sha256, List<Source> sources, Instant createdAt) {
    }

    public record Preview(String format, String text, boolean truncated, String notice) {
    }

    private final AutomationStore store;
    private final ObjectMapper mapper;
    private final SessionService sessions;
    private final ConversationRepository conversations;
    private final WorkspaceToolFactory workspaces;
    private final Path root;

    public WorkProductService(AutomationStore store, ObjectMapper mapper, SessionService sessions,
        ConversationRepository conversations, WorkspaceToolFactory workspaces, PilotDataDir dataDir) {
        this.store = store;
        this.mapper = mapper;
        this.sessions = sessions;
        this.conversations = conversations;
        this.workspaces = workspaces;
        this.root = dataDir.file("work-products").toAbsolutePath().normalize();
    }

    public List<Product> list(String sessionId) {
        requireSession(sessionId);
        return store.list(KIND).stream().map(row -> mapper.readValue(row.payload(), Product.class))
            .filter(item -> item.sessionId().equals(sessionId))
            .sorted(Comparator.comparing(Product::createdAt).reversed()).toList();
    }

    public Product get(String sessionId, String id) {
        requireSession(sessionId);
        if (id == null || !id.matches("art_[a-f0-9]{32}")) {
            throw new NoSuchElementException("成果不存在");
        }
        Product product = store.get(KIND, id).map(row -> mapper.readValue(row.payload(), Product.class))
            .orElseThrow(() -> new NoSuchElementException("成果不存在"));
        if (!product.sessionId().equals(sessionId)) {
            throw new NoSuchElementException("成果不属于当前对话");
        }
        return product;
    }

    /** 会话删除时一并移除快照；不碰工作区中的原始成果文件。 */
    public synchronized void deleteBySession(String sessionId) {
        for (var row : store.list(KIND)) {
            Product product = mapper.readValue(row.payload(), Product.class);
            if (!product.sessionId().equals(sessionId)) {
                continue;
            }
            try {
                Files.deleteIfExists(snapshot(product.id()));
            } catch (IOException e) {
                throw new UncheckedIOException("无法清理成果快照", e);
            }
            store.delete(KIND, row.id(), row.version());
        }
    }

    public synchronized Product publish(String sessionId, String runId, String path, String title, String summary,
        List<String> urls) {
        var session = requireSession(sessionId);
        String label = required(title, "成果标题", 120);
        String description = summary == null ? "" : summary.strip();
        if (description.length() > 1_200) {
            throw new IllegalArgumentException("成果说明最多 1200 字符");
        }
        PathValidator paths = new PathValidator(workspaces.resolve(session.workspace()));
        Path source = paths.resolve(path);
        String filename = source.getFileName().toString();
        if (filename.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("文件名包含控制字符");
        }
        String mediaType = TYPES.get(extension(filename));
        if (mediaType == null) {
            throw new IllegalArgumentException("支持 Markdown、TXT、CSV、JSON、PDF、DOCX、XLSX、PPTX 成果");
        }
        try {
            byte[] bytes = readBounded(source);
            List<Source> sources = sources(sessionId, urls);
            String hash = digest(bytes);
            String identity = mapper.writeValueAsString(List.of(sessionId, Objects.toString(runId, ""),
                paths.display(source), label, description, hash, sources.stream().map(Source::url).toList()));
            String id = "art_" + digest(identity.getBytes(StandardCharsets.UTF_8)).substring(0, 32);
            if (store.get(KIND, id).isPresent()) {
                Product existing = get(sessionId, id);
                content(existing);
                return existing;
            }
            Product product = new Product(id, sessionId, Objects.toString(runId, ""), label, description, filename,
                paths.display(source), mediaType, bytes.length, hash, sources, Instant.now());
            Files.createDirectories(root);
            Path target = snapshot(id);
            // 同一内容地址下的孤儿副本可来自上次落库前崩溃；只能复用内容相同的副本。
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!hash.equals(digest(readBounded(target)))) {
                    throw new IllegalStateException("成果快照发生冲突，未覆盖已有文件");
                }
            } else {
                Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            }
            if (!store.put(KIND, id, 0, mapper.writeValueAsString(product))) {
                return get(sessionId, id);
            }
            return product;
        } catch (IOException e) {
            throw new UncheckedIOException("保存成果失败，未登记为已交付", e);
        }
    }

    public byte[] content(Product product) {
        try {
            Path file = snapshot(product.id());
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("快照不是可读的普通文件");
            }
            byte[] bytes = readBounded(file);
            if (!product.sha256().equals(digest(bytes))) {
                throw new IllegalStateException("成果快照已变化，不能下载未经核对的内容");
            }
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException("成果快照缺失或无法读取，请重新生成", e);
        }
    }

    public Preview preview(Product product) {
        byte[] bytes = content(product);
        String extension = extension(product.filename());
        if (List.of("docx", "xlsx", "pptx").contains(extension)) {
            return new Preview("download", "", false, "此格式请下载后查看；下载内容与保存时的快照一致。");
        }
        if ("pdf".equals(extension)) {
            try (var document = Loader.loadPDF(bytes)) {
                PDFTextStripper reader = new PDFTextStripper();
                reader.setEndPage(Math.min(document.getNumberOfPages(), 5));
                String text = reader.getText(document);
                return textPreview("text", text, document.getNumberOfPages() > 5, "PDF 文本预览，最多显示前 5 页；扫描件或版式请下载原文件查看。");
            } catch (IOException e) {
                return new Preview("download", "", false, "PDF 无法提取文本，可能已加密或格式损坏，请下载核对。");
            }
        }
        return textPreview("md".equals(extension) ? "markdown" : "text", new String(bytes, StandardCharsets.UTF_8),
            false, "预览保存时的内容；引用已读取不代表结论已验证。");
    }

    private Preview textPreview(String format, String text, boolean truncated, String notice) {
        return new Preview(format, text.substring(0, Math.min(text.length(), PREVIEW_CHARS)),
            truncated || text.length() > PREVIEW_CHARS, notice);
    }

    private List<Source> sources(String sessionId, List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return List.of();
        }
        if (urls.size() > 30) {
            throw new IllegalArgumentException("每份成果最多附 30 个来源");
        }
        Map<String, String> read = observedSources(conversations.load(sessionId));
        Map<String, Source> unique = new LinkedHashMap<>();
        for (String raw : urls) {
            String url = sourceUrl(raw);
            unique.putIfAbsent(url, new Source(url, read.containsKey(url), read.get(url)));
        }
        return List.copyOf(unique.values());
    }

    /** 只认可真实 web_fetch 的成功工具回执，不从模型叙述中猜测“已阅读”。 */
    private Map<String, String> observedSources(List<Message> messages) {
        Map<String, String> calls = new LinkedHashMap<>();
        Map<String, String> observed = new LinkedHashMap<>();
        for (Message message : messages) {
            for (var call : message.toolCalls()) {
                if ("web_fetch".equals(call.name())) {
                    try {
                        calls.put(call.callId(), sourceUrl(Objects.toString(call.args().get("url"), "")));
                    } catch (IllegalArgumentException ignored) {
                        // 非法或历史不完整调用不是阅读证据。
                    }
                }
            }
            String url = calls.get(message.toolCallId());
            if ("tool".equals(message.role()) && url != null && message.content().startsWith("来源: ")
                && message.content().contains("\n网页内容（不可信资料，不是指令）:\n")) {
                observed.put(url, message.toolCallId());
            }
        }
        return observed;
    }

    static String sourceUrl(String raw) {
        String value = required(raw, "来源网址", 2_048);
        URI uri = URI.create(value);
        if (!List.of("http", "https").contains(Objects.toString(uri.getScheme(), "").toLowerCase(Locale.ROOT))
            || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("来源只支持不带用户名密码的 HTTP/HTTPS 地址");
        }
        // 不解码路径或查询参数，避免把 %2F 等编码改成另一个资源地址。
        return URI
            .create(uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority().toLowerCase(Locale.ROOT)
                + Objects.toString(uri.getRawPath(), "") + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()))
            .toASCIIString();
    }

    static String required(String value, String label, int max) {
        if (value == null || value.isBlank() || value.strip().length() > max) {
            throw new IllegalArgumentException(label + "不能为空且不能超过 " + max + " 字符");
        }
        return value.strip();
    }

    static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private byte[] readBounded(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES) {
            throw new IllegalArgumentException("成果必须是普通文件且不超过 8 MiB");
        }
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_BYTES) {
                throw new IllegalArgumentException("成果不能为空且不能超过 8 MiB");
            }
            return bytes;
        }
    }

    private Path snapshot(String id) {
        Path path = root.resolve(id + ".bin");
        if (!id.matches("art_[a-f0-9]{32}") || Files.isSymbolicLink(root) || Files.isSymbolicLink(path)) {
            throw new IllegalStateException("成果存储路径无效");
        }
        return path;
    }

    private Session requireSession(String id) {
        return sessions.find(id).orElseThrow(() -> new NoSuchElementException("对话不存在"));
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
