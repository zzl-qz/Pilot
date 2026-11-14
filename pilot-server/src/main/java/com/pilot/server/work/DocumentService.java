package com.pilot.server.work;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.pilot.core.tool.office.DocumentRenderer;
import com.pilot.core.util.FileNames;
import com.pilot.core.util.Sha256;
import com.pilot.core.workspace.PathValidator;
import com.pilot.server.common.data.PilotDataDir;
import com.pilot.server.store.AutomationStore;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * 产物工作台的可编辑源：版本化 Document 与派生产物 Artifact 分离。
 * 生成不经过模型，直接调用 {@link DocumentRenderer}，与 office 工具同源。
 */
@Service
@RequiredArgsConstructor
public class DocumentService {
    private static final String KIND = "studio-document";
    private static final String VERSION_KIND = "studio-version";
    private static final String ARTIFACT_KIND = "studio-artifact";
    private static final Pattern SAFE_ID = Pattern.compile("^(doc|sta)_[a-f0-9]{32}$");
    private static final long MAX_SPEC_BYTES = 2_000_000L;

    /** 可编辑源：version 由 AutomationStore 乐观锁管理，读取时以其为准。 */
    public record Document(String id, String sessionId, String title, String kind, String spec, String specHash,
        long version, String createdBy, String createdAt, String updatedAt) {
    }

    public record Version(long version, String spec, String specHash, String createdAt, String createdBy) {
    }

    /** 派生产物：永不原地覆盖，每次导出产生新记录。 */
    public record Artifact(String id, String documentId, long documentVersion, String format, String filename,
        long bytes, String sha256, String verification, String createdAt) {
    }

    public record RenderResult(Document document, List<Artifact> artifacts) {
    }

    public record Download(Path file, String filename, String mediaType) {
    }

    private final AutomationStore store;
    private final PilotDataDir dataDir;
    private final WorkProductService products;
    private final ObjectMapper json = new ObjectMapper();

    public List<Document> list(String sessionId) {
        return store.list(KIND).stream().map(this::parse).filter(doc -> doc.sessionId().equals(sessionId))
            .sorted(Comparator.comparing(Document::updatedAt).reversed()).toList();
    }

    public Document get(String sessionId, String id) {
        Document document = require(sessionId, id);
        return document;
    }

    public Document create(String sessionId, String title, String kind, String spec, String createdBy) {
        validateId(sessionId);
        String normalized = validateSpec(spec);
        String now = Instant.now().toString();
        Document document = new Document(newId("doc"), sessionId, title(title), normalizeKind(kind), normalized,
            Sha256.hex(normalized), 1, createdBy == null ? "human" : createdBy, now, now);
        if (!store.put(KIND, document.id(), 0, json.writeValueAsString(document))) {
            throw new IllegalStateException("文档标识冲突，请重试");
        }
        return document;
    }

    public Document update(String sessionId, String id, long version, String title, String spec, String createdBy) {
        Document current = require(sessionId, id);
        if (current.version() != version) {
            throw new IllegalArgumentException("文档已被其他编辑更新（当前版本 " + current.version() + "），请刷新后再保存");
        }
        String normalized = validateSpec(spec);
        String now = Instant.now().toString();
        Version snapshot = new Version(current.version(), current.spec(), current.specHash(), current.updatedAt(),
            current.createdBy());
        store.put(VERSION_KIND, id + ":" + current.version(), 0, json.writeValueAsString(snapshot));
        Document next = new Document(id, sessionId, title(title), current.kind(), normalized, Sha256.hex(normalized),
            version + 1, createdBy == null ? "human" : createdBy, current.createdAt(), now);
        if (!store.put(KIND, id, version, json.writeValueAsString(next))) {
            throw new IllegalArgumentException("并发更新冲突，请刷新后再保存");
        }
        return next;
    }

    public List<Version> versions(String sessionId, String id) {
        Document current = require(sessionId, id);
        List<Version> result = new ArrayList<>();
        String prefix = id + ":";
        for (var row : store.list(VERSION_KIND)) {
            if (row.id().startsWith(prefix)) {
                result.add(json.readValue(row.payload(), Version.class));
            }
        }
        result.add(new Version(current.version(), current.spec(), current.specHash(), current.updatedAt(),
            current.createdBy()));
        return result.stream().sorted(Comparator.comparingLong(Version::version).reversed()).toList();
    }

    /** 回滚不是删除历史：把目标版本的 spec 另存为一个新版本。 */
    public Document rollback(String sessionId, String id, long version, long targetVersion, String createdBy) {
        Document current = require(sessionId, id);
        Version target = versions(sessionId, id).stream().filter(item -> item.version() == targetVersion).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("目标版本不存在：" + targetVersion));
        return update(sessionId, id, version, current.title(), target.spec(), createdBy);
    }

    public RenderResult render(String sessionId, String id, JsonNode body) {
        Document document = require(sessionId, id);
        JsonNode formats = body == null ? null : body.path("formats");
        if (formats == null || !formats.isArray() || formats.isEmpty() || formats.size() > 4) {
            throw new IllegalArgumentException("formats 须为 1–4 个导出格式");
        }
        JsonNode spec;
        try {
            spec = json.readTree(document.spec());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("可编辑源不是合法 JSON，无法导出");
        }
        Path directory = documentDir(id);
        List<Artifact> artifacts = new ArrayList<>();
        List<Path> temps = new ArrayList<>();
        try {
            Files.createDirectories(directory);
            PathValidator paths = new PathValidator(dataDir.file("studio"));
            for (JsonNode item : formats) {
                if (!item.isTextual() || !DocumentRenderer.FORMATS.contains(item.asText())) {
                    throw new IllegalArgumentException("不支持的导出格式：" + item);
                }
                String format = item.asText();
                Path temp = directory.resolve(".tmp-" + UUID.randomUUID() + "." + format);
                temps.add(temp);
                String verification = DocumentRenderer.render(temp, format, spec, paths);
                JsonNode operations = body.path("pdf_operations");
                if ("pdf".equals(format) && operations.isObject() && operations.size() > 0) {
                    Path edited = directory.resolve(".tmp-" + UUID.randomUUID() + ".pdf");
                    temps.add(edited);
                    verification += "；" + DocumentRenderer.editPdf(temp, edited, operations, paths, directory);
                    Files.move(edited, temp, StandardCopyOption.REPLACE_EXISTING);
                }
                verification += "；读回：" + truncate(DocumentRenderer.inspect(temp));
                byte[] bytes = Files.readAllBytes(temp);
                String artifactId = newId("sta");
                String filename = artifactId + "." + format;
                Path target = directory.resolve(filename);
                Files.move(temp, target);
                Artifact artifact = new Artifact(artifactId, id, document.version(), format, filename, bytes.length,
                    Sha256.hex(bytes), verification, Instant.now().toString());
                if (!store.put(ARTIFACT_KIND, artifactId, 0, json.writeValueAsString(artifact))) {
                    throw new IllegalStateException("产物标识冲突，请重试");
                }
                artifacts.add(artifact);
            }
            return new RenderResult(document, artifacts);
        } catch (Exception e) {
            throw new IllegalStateException("导出失败：" + e.getMessage(), e);
        } finally {
            for (Path temp : temps) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 临时文件清理失败不影响已交付结果
                }
            }
        }
    }

    public List<Artifact> artifacts(String sessionId, String id) {
        require(sessionId, id);
        return store.list(ARTIFACT_KIND).stream().map(row -> json.readValue(row.payload(), Artifact.class))
            .filter(item -> item.documentId().equals(id)).sorted(Comparator.comparing(Artifact::createdAt).reversed())
            .toList();
    }

    public Download download(String sessionId, String id, String artifactId) {
        require(sessionId, id);
        if (!SAFE_ID.matcher(artifactId).matches()) {
            throw new IllegalArgumentException("产物标识非法");
        }
        Artifact artifact =
            store.get(ARTIFACT_KIND, artifactId).map(row -> json.readValue(row.payload(), Artifact.class))
                .filter(item -> item.documentId().equals(id)).orElseThrow(() -> new IllegalArgumentException("产物不存在"));
        if (!DocumentRenderer.isRenderable(artifact.filename())
            || !artifact.filename().equals(artifactId + "." + artifact.format())) {
            throw new IllegalArgumentException("产物文件名与记录不一致");
        }
        Path file = documentDir(id).resolve(artifact.filename());
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("产物文件已丢失，请重新导出");
        }
        return new Download(file, artifact.filename(), mediaType(artifact.format()));
    }

    /** 从已有成果快照导入可编辑源：只带文本，明确不含原排版。 */
    public Document importProduct(String sessionId, String productId, String title) {
        var product = products.list(sessionId).stream().filter(item -> item.id().equals(productId)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("成果不存在：" + productId));
        byte[] bytes = products.content(product);
        String suffix = FileNames.dotExtension(product.filename());
        if (!DocumentRenderer.isRenderable(product.filename())) {
            throw new IllegalArgumentException("该成果不是可导入的 Office 文件");
        }
        Path directory = dataDir.file("studio", "imports");
        Path temp = directory.resolve(".import-" + UUID.randomUUID() + suffix);
        List<String> blocks = new ArrayList<>();
        try {
            Files.createDirectories(directory);
            Files.write(temp, bytes);
            String text = DocumentRenderer.inspect(temp);
            for (String line : text.split("\\R")) {
                if (!line.isBlank() && blocks.size() < 200) {
                    blocks.add(line.strip());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取成果失败：" + e.getMessage(), e);
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 导入临时文件清理失败不影响已创建的文档
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title(title));
        payload.put("blocks",
            blocks.stream().map(line -> Map.<String, Object>of("type", "paragraph", "text", line)).toList());
        payload.put("imported_text_only", true);
        return create(sessionId, title, "blocks", json.writeValueAsString(payload), "import");
    }

    private Document require(String sessionId, String id) {
        if (!SAFE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("文档标识非法");
        }
        return store.get(KIND, id).map(this::parse).filter(doc -> doc.sessionId().equals(sessionId))
            .orElseThrow(() -> new IllegalArgumentException("文档不存在：" + id));
    }

    private Document parse(AutomationStore.Document row) {
        Document document = json.readValue(row.payload(), Document.class);
        return new Document(document.id(), document.sessionId(), document.title(), document.kind(), document.spec(),
            document.specHash(), row.version(), document.createdBy(), document.createdAt(), document.updatedAt());
    }

    private Path documentDir(String id) {
        return dataDir.file("studio", id);
    }

    private static String validateSpec(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("spec 不能为空");
        }
        if (spec.getBytes(StandardCharsets.UTF_8).length > MAX_SPEC_BYTES) {
            throw new IllegalArgumentException("spec 超过 2 MB 上限");
        }
        JsonNode node;
        try {
            node = new ObjectMapper().readTree(spec);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("spec 不是合法 JSON");
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("spec 必须是 JSON 对象");
        }
        return node.toString();
    }

    private static void validateId(String sessionId) {
        if (sessionId == null || !sessionId.matches("^[A-Za-z0-9_-]{1,128}$")) {
            throw new IllegalArgumentException("会话标识非法");
        }
    }

    private static String normalizeKind(String kind) {
        return "spec".equals(kind) ? "spec" : "blocks";
    }

    private static String title(String title) {
        if (title == null || title.isBlank()) {
            return "未命名文档";
        }
        return title.length() > 120 ? title.substring(0, 120) : title.strip();
    }

    private static String mediaType(String format) {
        return switch (format) {
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "pdf" -> "application/pdf";
            default -> "application/octet-stream";
        };
    }

    private static String truncate(String text) {
        return text.length() > 2_000 ? text.substring(0, 2_000) + "…" : text;
    }

    private static String newId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

}
