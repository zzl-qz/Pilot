package com.pilot.server.work;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import org.springframework.stereotype.Service;

import com.pilot.core.session.SessionService;
import com.pilot.core.tool.office.DocumentRenderer;
import com.pilot.core.tool.office.FilePreviews;
import com.pilot.core.util.FileTrees;
import com.pilot.core.util.Sha256;
import com.pilot.core.workspace.PathValidator;
import com.pilot.server.browser.BrowserRuntime;
import com.pilot.server.common.data.PilotDataDir;
import com.pilot.server.common.web.SessionLookup;
import com.pilot.server.store.AutomationStore;
import com.pilot.server.tool.WorkspaceToolFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * 文件工作台：工作区里的真实 Office 文件就是唯一源，这里只做索引、预览、批注与交付。
 *
 * <p>对标 Codex 的审阅模式：不维护第二套「可编辑源」，不把文件复制进私有目录；
 * 用户可以随时用本机应用改同一个文件，工作台通过 sha256 感知改动。
 */
@Service
@RequiredArgsConstructor
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

    // 有界分段锁，避免每个历史文件留下一个永久锁对象；回退和快照共用同一把锁。
    private final Object[] versionLocks = IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();
    private final AutomationStore store;
    private final ObjectMapper mapper;
    private final WorkspaceToolFactory workspaces;
    private final SessionService sessions;
    private final PilotDataDir dataDir;
    private final WorkProductService products;

    /** 扫描会话工作区里的真实产物文件；不做任何复制，也不把内容存进数据库。 */
    public List<FileAsset> list(String sessionId) throws IOException {
        Path root = root(sessionId);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<FileAsset> assets = new ArrayList<>();
        Map<String, Version> latest = latestVersions(sessionId);
        for (Path file : scan(root)) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            assets.add(describe(sessionId, file, relative, latest.get(assetId(relative))));
        }
        assets.sort(Comparator.comparing(FileAsset::path));
        return assets;
    }

    public FileAsset asset(String sessionId, String id) throws IOException {
        Path file = resolve(sessionId, id);
        String relative = root(sessionId).relativize(file).toString().replace('\\', '/');
        List<Version> versions = versions(sessionId, id);
        return describe(sessionId, file, relative, versions.isEmpty() ? null : versions.getLast());
    }

    public Path resolve(String sessionId, String id) throws IOException {
        Path root = root(sessionId);
        // 只扫描路径，不为一次下载/预览计算整个工作区的哈希或读取所有版本。
        for (Path file : scan(root)) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            if (assetId(relative).equals(id)) {
                return new PathValidator(root).resolve(relative);
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
        String sha = Sha256.hex(file, LinkOption.NOFOLLOW_LINKS);
        Path directory = dataDir.file("file-workbench", sessionId, id, sha, "pages");
        FilePreviews.Result result = FilePreviews.generate(file, directory);
        return new PreviewInfo(id, result.pages(), result.notice());
    }

    public Download previewPage(String sessionId, String id, int page) throws IOException {
        Path file = resolve(sessionId, id);
        Path directory =
            dataDir.file("file-workbench", sessionId, id, Sha256.hex(file, LinkOption.NOFOLLOW_LINKS), "pages");
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
        return readMetadata(VERSION_KIND, key(sessionId, id, ""), Version.class).values().stream()
            .sorted(Comparator.comparingLong(Version::version)).toList();
    }

    /** 快照是 copy-on-write：只复制一份到运行期目录，工作区文件始终原地可编辑。 */
    public Version snapshot(String sessionId, String id, String label) throws IOException {
        synchronized (versionLock(sessionId, id)) {
            Path file = resolve(sessionId, id);
            List<Version> existing = versions(sessionId, id);
            return snapshotFile(sessionId, id, file, label, nextVersion(existing));
        }
    }

    private Version snapshotFile(String sessionId, String id, Path file, String label, long next) throws IOException {
        Path directory = dataDir.file("file-versions", sessionId, id);
        Files.createDirectories(directory);
        Path saved = directory.resolve("v" + next + extension(file));
        // CREATE_NEW 同时保护磁盘上未登记的旧快照，任何冲突都明确失败。
        var output = Files.newOutputStream(saved, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        boolean committed = false;
        try {
            try (output; InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                input.transferTo(output);
            }
            Version version = new Version(next, Sha256.hex(saved, LinkOption.NOFOLLOW_LINKS), Instant.now().toString(),
                label == null || label.isBlank() ? "快照 " + next : label.trim());
            if (!store.put(VERSION_KIND, key(sessionId, id, String.valueOf(next)), 0, write(version))) {
                throw new IOException("快照版本冲突，请刷新后重试：v" + next);
            }
            committed = true;
            return version;
        } finally {
            if (!committed) {
                Files.deleteIfExists(saved);
            }
        }
    }

    private static long nextVersion(List<Version> versions) {
        return versions.isEmpty() ? 1L : versions.getLast().version() + 1;
    }

    /** 回退先把当前状态另存为新快照，再覆盖工作区文件，不丢失任何历史。 */
    public Version revert(String sessionId, String id, long version) throws IOException {
        synchronized (versionLock(sessionId, id)) {
            Path file = resolve(sessionId, id);
            Path saved = dataDir.file("file-versions", sessionId, id).resolve("v" + version + extension(file));
            List<Version> existing = versions(sessionId, id);
            if (!Files.isRegularFile(saved, LinkOption.NOFOLLOW_LINKS)
                || existing.stream().noneMatch(item -> item.version() == version)) {
                throw new IllegalArgumentException("快照 v" + version + " 不存在，无法回退");
            }
            long next = nextVersion(existing);
            snapshotFile(sessionId, id, file, "回退前的当前版本", next);
            Files.copy(saved, file, StandardCopyOption.REPLACE_EXISTING);
            return snapshotFile(sessionId, id, file, "回退到 v" + version, next + 1);
        }
    }

    private Object versionLock(String sessionId, String id) {
        return versionLocks[Math.floorMod(key(sessionId, id, "").hashCode(), versionLocks.length)];
    }

    public List<Annotation> annotations(String sessionId, String id) {
        return readMetadata(ANNOTATION_KIND, key(sessionId, id, ""), Annotation.class).values().stream()
            .sorted(Comparator.comparing(Annotation::createdAt)).toList();
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
            for (AutomationStore.Document document : store.list(kind, sessionId + ":")) {
                store.delete(kind, document.id(), document.version());
            }
        }
        for (String folder : List.of("file-workbench", "file-versions")) {
            Path cache = dataDir.file(folder, sessionId);
            if (Files.exists(cache)) {
                FileTrees.deleteBestEffort(cache);
            }
        }
    }

    private FileAsset assetByPath(String sessionId, Path root, Path file) throws IOException {
        String relative = root.relativize(file).toString().replace('\\', '/');
        return describe(sessionId, file, relative, null);
    }

    private static FileAsset describe(String sessionId, Path file, String relative, Version latest) throws IOException {
        String sha = Sha256.hex(file, LinkOption.NOFOLLOW_LINKS);
        BasicFileAttributes attributes =
            Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return new FileAsset(assetId(relative), sessionId, relative, file.getFileName().toString(),
            format(relative).orElseThrow(), attributes.size(), sha,
            attributes.lastModifiedTime().toInstant().toString(), latest != null && !latest.sha256().equals(sha));
    }

    private Path root(String sessionId) throws IOException {
        String workspace =
            sessions.find(sessionId).orElseThrow(() -> new IllegalArgumentException(SessionLookup.MISSING)).workspace();
        Path root = workspaces.resolve(workspace);
        return Files.isDirectory(root) ? root.toRealPath() : root.toAbsolutePath().normalize();
    }

    private static String key(String sessionId, String id, String suffix) {
        return sessionId + ":" + id + ":" + suffix;
    }

    private static String assetId(String relative) {
        return "fil_" + Sha256.hex(relative).substring(0, 32);
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

    private static boolean hidden(Path file) {
        String name = file.getFileName().toString();
        return name.startsWith(".") || SKIP_DIRECTORIES.contains(name);
    }

    private static List<Path> scan(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        PathValidator validator = new PathValidator(root);
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                return !directory.equals(root) && hidden(directory) ? FileVisitResult.SKIP_SUBTREE
                    : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                // 不跟随文件或目录链接；每次操作重新校验，旧列表不能授予文件读取权限。
                if (attrs.isRegularFile() && !hidden(file) && format(file.toString()).isPresent() && attrs.size() > 0
                    && attrs.size() <= MAX_FILE_BYTES) {
                    try {
                        files.add(validator.resolve(file.toString()));
                    } catch (RuntimeException ignored) {
                        // 扫描期间被移动/替换成工作区外链接的文件不可访问。
                    }
                }
                return files.size() >= MAX_FILES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    /** 列表一次读取版本元数据，避免每个文件都扫描全部历史。 */
    private Map<String, Version> latestVersions(String sessionId) {
        Map<String, Version> latest = new HashMap<>();
        String prefix = sessionId + ":";
        readMetadata(VERSION_KIND, prefix, Version.class).forEach((key, version) -> {
            int end = key.lastIndexOf(':');
            if (end >= prefix.length()) {
                String id = key.substring(prefix.length(), end);
                latest.merge(id, version, (left, right) -> left.version() > right.version() ? left : right);
            }
        });
        return latest;
    }

    private <T> Map<String, T> readMetadata(String kind, String prefix, Class<T> type) {
        Map<String, T> result = new LinkedHashMap<>();
        for (AutomationStore.Document document : store.list(kind, prefix)) {
            try {
                T value = mapper.readValue(document.payload(), type);
                if (value != null) {
                    result.put(document.id(), value);
                }
            } catch (RuntimeException ignored) {
                // 单条损坏的元数据不影响其他记录及工作区文件。
            }
        }
        return result;
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
}
