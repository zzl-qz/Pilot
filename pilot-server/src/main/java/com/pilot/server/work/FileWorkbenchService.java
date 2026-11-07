package com.pilot.server.work;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.pilot.core.session.SessionService;
import com.pilot.core.tool.DocumentRenderer;
import com.pilot.core.tool.FilePreviews;
import com.pilot.core.workspace.PathValidator;
import com.pilot.server.browser.BrowserRuntime;
import com.pilot.server.config.PilotDataDir;
import com.pilot.server.store.AutomationStore;
import com.pilot.server.workspace.WorkspaceToolFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 文件工作台：工作区里的真实 Office 文件就是唯一源，这里只做索引、预览、批注与交付。
 *
 * <p>对标 Codex 的审阅模式：不维护第二套「可编辑源」，不把文件复制进私有目录；
 * 用户可以随时用本机应用改同一个文件，工作台通过 sha256 感知改动。
 */
@Service
public class FileWorkbenchService {
    private static final String ANNOTATION_KIND = "file-annotation";
    private static final String VERSION_KIND = "file-version";
    private static final Set<String> FORMATS = Set.of(".docx", ".xlsx", ".pptx", ".pdf");
    private static final Set<String> SKIP_DIRECTORIES =
        Set.of(".git", ".pilot", ".gradle", ".idea", ".vscode", "node_modules", "target", "build", "out", "dist");
    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    private static final int MAX_FILES = 200;
    private static final int MAX_DEPTH = 5;

    public record FileAsset(String id, String sessionId, String path, String filename, String format, long bytes,
        String sha256, String modifiedAt, boolean changedExternally) {
    }

    public record PreviewInfo(String fileId, int pages, String notice) {
    }

    public record Version(long version, String sha256, String createdAt, String label) {
    }

    public record Annotation(String id, String fileId, int page, String anchor, String text, String createdAt,
        String status) {
    }

    public record OpenResult(boolean ok, String reason, String alternative) {
    }

    public record Download(String filename, String contentType, byte[] content) {
    }

    private final AutomationStore store;
    private final ObjectMapper mapper;
    private final WorkspaceToolFactory workspaces;
    private final SessionService sessions;
    private final PilotDataDir dataDir;
    private final WorkProductService products;

    public FileWorkbenchService(AutomationStore store, ObjectMapper mapper, WorkspaceToolFactory workspaces,
        SessionService sessions, PilotDataDir dataDir, WorkProductService products) {
        this.store = store;
        this.mapper = mapper;
        this.workspaces = workspaces;
        this.sessions = sessions;
        this.dataDir = dataDir;
        this.products = products;
    }

    /** 扫描会话工作区里的真实产物文件；不做任何复制，也不把内容存进数据库。 */
    public List<FileAsset> list(String sessionId) throws IOException {
        Path root = root(sessionId);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<FileAsset> assets = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root, MAX_DEPTH)) {
            for (Path file : (Iterable<Path>)paths.filter(Files::isRegularFile)::iterator) {
                if (assets.size() >= MAX_FILES) {
                    break;
                }
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (hidden(root, file) || !format(relative).isPresent() || Files.size(file) > MAX_FILE_BYTES
                    || Files.size(file) == 0) {
                    continue;
                }
                String sha = sha256(file);
                String id = assetId(relative);
                assets.add(new FileAsset(id, sessionId, relative, file.getFileName().toString(),
                    format(relative).orElseThrow(), Files.size(file), sha,
                    Files.getLastModifiedTime(file).toInstant().toString(), changedExternally(sessionId, id, sha)));
            }
        }
        assets.sort(Comparator.comparing(FileAsset::path));
        return assets;
    }

    public FileAsset asset(String sessionId, String id) throws IOException {
        Path file = resolve(sessionId, id);
        String relative = root(sessionId).relativize(file).toString().replace('\\', '/');
        String sha = sha256(file);
        return new FileAsset(id, sessionId, relative, file.getFileName().toString(), format(relative).orElseThrow(),
            Files.size(file), sha, Files.getLastModifiedTime(file).toInstant().toString(),
            changedExternally(sessionId, id, sha));
    }

    public Path resolve(String sessionId, String id) throws IOException {
        Path root = root(sessionId);
        for (FileAsset asset : list(sessionId)) {
            if (asset.id().equals(id)) {
                Path file = root.resolve(asset.path()).normalize();
                if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                    throw new IllegalArgumentException("文件已不在工作区中：" + asset.path());
                }
                return file;
            }
        }
        throw new IllegalArgumentException("找不到文件：" + id + "。文件可能已被移动、改名或删除，请刷新列表。");
    }

    /** 纯文本读回，用于内容核对；不含排版。 */
    public String inspect(String sessionId, String id) throws IOException {
        return DocumentRenderer.inspect(resolve(sessionId, id));
    }

    public PreviewInfo preview(String sessionId, String id) throws IOException {
        Path file = resolve(sessionId, id);
        String sha = sha256(file);
        Path directory = dataDir.file("file-workbench", sessionId, id, sha, "pages");
        FilePreviews.Result result = FilePreviews.generate(file, directory);
        return new PreviewInfo(id, result.pages(), result.notice());
    }

    public Download previewPage(String sessionId, String id, int page) throws IOException {
        Path file = resolve(sessionId, id);
        Path directory = dataDir.file("file-workbench", sessionId, id, sha256(file), "pages");
        FilePreviews.Result result = FilePreviews.generate(file, directory);
        if (page < 1 || page > result.pages()) {
            throw new IllegalArgumentException("页码超出范围：1.." + result.pages());
        }
        Path image = directory.resolve("page-" + page + ".png");
        if (!Files.isRegularFile(image)) {
            throw new IOException("预览图片缺失，请重新生成预览");
        }
        return new Download("page-" + page + ".png", "image/png", Files.readAllBytes(image));
    }

    public Download download(String sessionId, String id) throws IOException {
        Path file = resolve(sessionId, id);
        return new Download(file.getFileName().toString(), contentType(file.getFileName().toString()),
            Files.readAllBytes(file));
    }

    /** 用本机默认应用打开；能力不足时明确反馈并给出下载这个替代操作。 */
    public OpenResult open(String sessionId, String id) throws IOException {
        Path file = resolve(sessionId, id);
        if (!BrowserRuntime.desktopAvailable() || !Desktop.isDesktopSupported()) {
            return new OpenResult(false, "当前没有可用的图形桌面或系统文件关联，无法调用本机应用。",
                "请点「下载文件」保存到本机，再用 Word / Excel / PowerPoint 打开编辑。");
        }
        try {
            Desktop.getDesktop().open(file.toFile());
            return new OpenResult(true, "已在系统默认应用中打开 " + file.getFileName(), "");
        } catch (IOException | UnsupportedOperationException e) {
            return new OpenResult(false, "系统没有为 " + extension(file) + " 关联可用的应用：" + e.getMessage(),
                "请点「下载文件」保存到本机，再手动选择打开方式。");
        }
    }

    /** 把工作区文件登记为成果：文件本来就在 workspace 里，publish_artifact 物理可达。 */
    public Object publish(String sessionId, String id, String title, String summary) throws IOException {
        FileAsset asset = asset(sessionId, id);
        return products.publish(sessionId, null, asset.path(),
            title == null || title.isBlank() ? asset.filename() : title.trim(),
            summary == null || summary.isBlank() ? "从文件工作台发布的 " + asset.filename() : summary.trim(), List.of());
    }

    public List<Version> versions(String sessionId, String id) {
        List<Version> versions = new ArrayList<>();
        for (AutomationStore.Document document : store.list(VERSION_KIND)) {
            if (!document.id().startsWith(sessionId + ":" + id + ":")) {
                continue;
            }
            try {
                versions.add(mapper.readValue(document.payload(), Version.class));
            } catch (Exception ignored) {
                // 损坏的元数据不应影响文件本身
            }
        }
        versions.sort(Comparator.comparingLong(Version::version));
        return versions;
    }

    /** 快照是 copy-on-write：只复制一份到运行期目录，工作区文件始终原地可编辑。 */
    public Version snapshot(String sessionId, String id, String label) throws IOException {
        Path file = resolve(sessionId, id);
        List<Version> existing = versions(sessionId, id);
        long next = existing.isEmpty() ? 1L : existing.get(existing.size() - 1).version() + 1;
        Version version = new Version(next, sha256(file), Instant.now().toString(),
            label == null || label.isBlank() ? "快照 " + next : label.trim());
        Path directory = dataDir.file("file-versions", sessionId, id);
        Files.createDirectories(directory);
        Files.copy(file, directory.resolve("v" + next + extension(file)), StandardCopyOption.REPLACE_EXISTING);
        store.put(VERSION_KIND, key(sessionId, id, String.valueOf(next)), 0, write(version));
        return version;
    }

    /** 回退先把当前状态另存为新快照，再覆盖工作区文件，不丢失任何历史。 */
    public Version revert(String sessionId, String id, long version) throws IOException {
        Path file = resolve(sessionId, id);
        Path saved = dataDir.file("file-versions", sessionId, id).resolve("v" + version + extension(file));
        if (!Files.isRegularFile(saved)) {
            throw new IllegalArgumentException("快照 v" + version + " 不存在，无法回退");
        }
        snapshot(sessionId, id, "回退前的当前版本");
        Files.copy(saved, file, StandardCopyOption.REPLACE_EXISTING);
        return snapshot(sessionId, id, "回退到 v" + version);
    }

    public List<Annotation> annotations(String sessionId, String id) {
        List<Annotation> result = new ArrayList<>();
        for (AutomationStore.Document document : store.list(ANNOTATION_KIND)) {
            if (!document.id().startsWith(sessionId + ":" + id + ":")) {
                continue;
            }
            try {
                result.add(mapper.readValue(document.payload(), Annotation.class));
            } catch (Exception ignored) {
                // 损坏的批注不应影响文件本身
            }
        }
        result.sort(Comparator.comparing(Annotation::createdAt));
        return result;
    }

    public Annotation annotate(String sessionId, String id, int page, String anchor, String text) throws IOException {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("批注内容不能为空");
        }
        PreviewInfo preview = preview(sessionId, id);
        int boundedPage = Math.max(1, Math.min(page, Math.max(1, preview.pages())));
        Annotation annotation = new Annotation("ann_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
            id, boundedPage, anchor == null ? "" : anchor.trim(), text.trim(), Instant.now().toString(), "OPEN");
        store.put(ANNOTATION_KIND, key(sessionId, id, annotation.id()), 0, write(annotation));
        // 建一次快照，便于用户用本机应用改完后感知「外部已修改」。
        if (versions(sessionId, id).isEmpty()) {
            snapshot(sessionId, id, "批注前自动快照");
        }
        return annotation;
    }

    /** 把批注翻译成可直接发给模型的「审阅指导」，要求最小改动范围。 */
    public String changeRequest(String sessionId, String id, String annotationId) throws IOException {
        FileAsset asset = asset(sessionId, id);
        Annotation annotation = annotations(sessionId, id).stream().filter(item -> item.id().equals(annotationId))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("找不到批注：" + annotationId));
        StringBuilder prompt = new StringBuilder();
        prompt.append("请按下面的审阅批注修改工作区里的真实文件，保持最小改动范围。\n");
        prompt.append("文件：").append(asset.path()).append('\n');
        prompt.append("格式：").append(asset.format()).append('\n');
        prompt.append("位置：第 ").append(annotation.page()).append(" 页");
        if (!annotation.anchor().isBlank()) {
            prompt.append("，锚点 ").append(annotation.anchor());
        }
        prompt.append('\n').append("批注：").append(annotation.text()).append('\n');
        prompt.append("要求：以该文件为 source_path，用 office 的 edit_").append(asset.format());
        prompt.append("（PDF 用页面级操作）生成修订版，只改批注指向的部分，其它内容保持原样；");
        prompt.append("按工具约定另存到同一目录的新文件名，并用 publish_artifact 交付；");
        prompt.append("完成后用 office inspect 读回核对，报告输出路径与检查结果。");
        prompt.append("文件工作台会自动索引新产物。不要把纯文本改扩展名冒充 Office 文件。\n");
        return prompt.toString();
    }

    /** PDF 页面级操作是确定性动作：结果另存为新文件，绝不覆盖可编辑源。 */
    public FileAsset pdfOperations(String sessionId, String id, JsonNode operations) throws IOException {
        Path source = resolve(sessionId, id);
        if (!source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException("页面级操作只支持 PDF，可编辑源请用本机应用修改");
        }
        if (operations == null || operations.size() == 0) {
            throw new IllegalArgumentException("请至少选择一个页面操作");
        }
        Path root = root(sessionId);
        String name = stripExtension(source.getFileName().toString()) + "-页面操作-"
            + UUID.randomUUID().toString().substring(0, 8) + ".pdf";
        Path target = source.getParent().resolve(name).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("输出路径越界");
        }
        DocumentRenderer.editPdf(source, target, operations, new PathValidator(root), source.getParent());
        return assetByPath(sessionId, root, target);
    }

    /** 会话结束时只清理运行期元数据与预览缓存，工作区文件由用户保留。 */
    public void deleteBySession(String sessionId) {
        for (String kind : List.of(ANNOTATION_KIND, VERSION_KIND)) {
            for (AutomationStore.Document document : store.list(kind)) {
                if (document.id().startsWith(sessionId + ":")) {
                    store.delete(kind, document.id(), document.version());
                }
            }
        }
        for (String folder : List.of("file-workbench", "file-versions")) {
            deleteTree(dataDir.file(folder, sessionId));
        }
    }

    private FileAsset assetByPath(String sessionId, Path root, Path file) throws IOException {
        String relative = root.relativize(file).toString().replace('\\', '/');
        String sha = sha256(file);
        return new FileAsset(assetId(relative), sessionId, relative, file.getFileName().toString(),
            format(relative).orElseThrow(), Files.size(file), sha,
            Files.getLastModifiedTime(file).toInstant().toString(), false);
    }

    private boolean changedExternally(String sessionId, String id, String currentSha) {
        List<Version> versions = versions(sessionId, id);
        return !versions.isEmpty() && !versions.get(versions.size() - 1).sha256().equals(currentSha);
    }

    private Path root(String sessionId) {
        String workspace =
            sessions.find(sessionId).orElseThrow(() -> new IllegalArgumentException("会话不存在")).workspace();
        return workspaces.resolve(workspace);
    }

    private static String key(String sessionId, String id, String suffix) {
        return sessionId + ":" + id + ":" + suffix;
    }

    private static String assetId(String relative) {
        return "fil_" + sha256Text(relative).substring(0, 32);
    }

    private static Optional<String> format(String relative) {
        String extension = extension(Path.of(relative)).toLowerCase(Locale.ROOT);
        return FORMATS.contains(extension) ? Optional.of(extension.substring(1)) : Optional.empty();
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static boolean hidden(Path root, Path file) {
        for (Path part : root.relativize(file)) {
            String name = part.toString();
            if (name.startsWith(".") || SKIP_DIRECTORIES.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256Text(String value) {
        try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(Object value) throws IOException {
        return mapper.writeValueAsString(value);
    }

    private static String contentType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lower.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (lower.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        if (lower.endsWith(".pptx")) {
            return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        }
        return "application/octet-stream";
    }

    private static void deleteTree(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException ignored) {
                    // 运行期缓存清理失败不影响会话删除
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

}
