package com.pilot.core.memory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import com.pilot.core.util.HostOs;
import com.pilot.core.util.Sha256;
import com.pilot.core.workspace.RealPaths;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 外置长期记忆存储（位于 Pilot 数据目录下，绝不修改目标代码仓库）。
 *
 * <p>同一 Git 仓库的多个 worktree 通过 {@link #resolveProjectRoot(Path)} 共享同一个项目记忆槽位；
 * 采用「{@code MEMORY.md} 薄索引 + {@code topics/<slug>.md} 按需主题正文」两层结构，
 * 候选区 {@code drafts.json} 必须经人工审阅采纳后才会进入索引并注入提示词。
 */
public final class MemoryStore {
    public static final String INDEX_FILE = "MEMORY.md";
    public static final String TOPICS_DIR = "topics";
    public static final String DRAFTS_FILE = "drafts.json";
    public static final String SCOPE_PROJECT = "project";
    public static final String SCOPE_USER = "user";

    public static final int MAX_INDEX_LINES = 200;
    public static final int MAX_INDEX_CHARS = 4_000;
    public static final int MAX_INDEX_ENTRY_CHARS = 150;
    public static final int MAX_NOTE_CHARS = 500;
    public static final int MAX_WHY_CHARS = 500;
    public static final int MAX_TITLE_CHARS = 80;
    public static final int MAX_BODY_CHARS = 8_000;

    private static final int KEY_LENGTH = 16;
    private static final int MAX_FRONTMATTER_CHARS = 8_192;
    private static final int MAX_LEGACY_DRAFT_BYTES = 262_144;
    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
    private static final Pattern LEGACY_DATE_TAG = Pattern.compile("^\\[(\\d{4}-\\d{2}-\\d{2})]\\s*");
    private static final Pattern LEGACY_TYPE_TAG =
        Pattern.compile("^\\[(user|feedback|project|reference)]\\s*", Pattern.CASE_INSENSITIVE);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Draft>> DRAFT_LIST = new TypeReference<>() {};
    private static final String INDEX_HEADER = "# 记忆索引（由 Pilot 维护，需要详情时调用 read_memory）\n";
    private static final ConcurrentHashMap<Path, Object> ROOT_LOCKS = new ConcurrentHashMap<>();

    public record Draft(String id, String type, String title, String note, String why, String date, String createdAt) {
    }

    public record Entry(String slug, String scope, String type, String title, String description, String body,
        String updated) {
    }

    public record IndexView(String text, boolean truncated, int entryCount) {
    }

    public record ImportResult(int imported, int skipped, boolean sourceFound) {
    }

    private final Path root;

    public MemoryStore(Path root) {
        this.root = Objects.requireNonNull(root, "root must not be null").toAbsolutePath().normalize();
    }

    private Object lock() {
        return ROOT_LOCKS.computeIfAbsent(root, ignored -> new Object());
    }

    public Path root() {
        return root;
    }

    public Path projectDir(Path workspace) {
        return root.resolve("projects").resolve(projectKey(workspace));
    }

    public Path userDir() {
        return root.resolve("user");
    }

    /**
     * 解析工作区归属的项目根目录：普通 Git 仓库返回仓根，Git worktree 回溯到主仓根目录，
     * 非 Git 目录回退为规范化工作区路径。
     */
    public static Path resolveProjectRoot(Path workspace) {
        Path base = workspace == null ? Path.of(".") : workspace;
        Path normalized = base.toAbsolutePath().normalize();
        Path realStart = RealPaths.directory(normalized);
        Path start = realStart != null ? realStart : normalized;
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            Path dotGit = dir.resolve(".git");
            if (Files.isSymbolicLink(dotGit)) {
                continue;
            }
            if (Files.isDirectory(dotGit, LinkOption.NOFOLLOW_LINKS)) {
                return realOrNormalized(dir);
            }
            if (Files.isRegularFile(dotGit, LinkOption.NOFOLLOW_LINKS)) {
                Path resolved = resolveWorktreeRoot(dir, dotGit);
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        return realOrNormalized(start);
    }

    public static String projectKey(Path workspace) {
        String path = resolveProjectRoot(workspace).toString();
        if (HostOs.isWindows()) {
            path = path.toLowerCase(Locale.ROOT);
        }
        return Sha256.hex(path).substring(0, KEY_LENGTH);
    }

    public static void validateSlug(String slug) {
        if (slug == null || !SLUG_PATTERN.matcher(slug).matches()) {
            throw new IllegalArgumentException("记忆标识 slug 须为 1–64 位小写字母、数字或连字符");
        }
    }

    public static String validateScope(String scope) {
        String normalized = scope == null ? "" : scope.strip().toLowerCase(Locale.ROOT);
        if (!SCOPE_PROJECT.equals(normalized) && !SCOPE_USER.equals(normalized)) {
            throw new IllegalArgumentException("记忆作用域只支持 project 或 user");
        }
        return normalized;
    }

    public Draft addDraft(Path workspace, MemoryType type, String title, String note, String why) {
        synchronized (lock()) {
            MemoryType actualType = type == null ? MemoryType.FEEDBACK : type;
            String cleanNote = validateText(note, "记忆正文 note", MAX_NOTE_CHARS, true);
            String cleanWhy = validateOptionalText(why, "原因 why", MAX_WHY_CHARS, true);
            String cleanTitle = deriveTitle(title, cleanNote);

            Path dir = projectDir(workspace);
            Path file = dir.resolve(DRAFTS_FILE);
            List<Draft> drafts = new ArrayList<>(readDrafts(file));
            for (Draft existing : drafts) {
                if (actualType.wire().equals(existing.type()) && cleanNote.equals(existing.note())) {
                    return existing;
                }
            }
            String today = LocalDate.now().toString();
            Draft created = new Draft("mem_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                actualType.wire(), cleanTitle, cleanNote, cleanWhy, today, Instant.now().toString());
            drafts.add(created);
            writeJson(file, drafts);
            return created;
        }
    }

    public List<Draft> listDrafts(Path workspace) {
        synchronized (lock()) {
            return List.copyOf(readDrafts(projectDir(workspace).resolve(DRAFTS_FILE)));
        }
    }

    public void discardDraft(Path workspace, String draftId) {
        synchronized (lock()) {
            if (draftId == null || draftId.isBlank()) {
                throw new IllegalArgumentException("缺少草稿 id");
            }
            Path file = projectDir(workspace).resolve(DRAFTS_FILE);
            List<Draft> drafts = new ArrayList<>(readDrafts(file));
            boolean removed = drafts.removeIf(item -> draftId.equals(item.id()));
            if (!removed) {
                throw new NoSuchElementException("记忆草稿不存在：" + draftId);
            }
            if (drafts.isEmpty()) {
                deleteIfRegular(file);
            } else {
                writeJson(file, drafts);
            }
        }
    }

    public Entry approveDraft(Path workspace, String draftId, String scope, MemoryType typeOverride,
        String titleOverride, String noteOverride, String whyOverride) {
        synchronized (lock()) {
            if (draftId == null || draftId.isBlank()) {
                throw new IllegalArgumentException("缺少草稿 id");
            }
            String targetScope = validateScope(scope);
            Path draftFile = projectDir(workspace).resolve(DRAFTS_FILE);
            List<Draft> drafts = new ArrayList<>(readDrafts(draftFile));
            Draft found = drafts.stream().filter(item -> draftId.equals(item.id())).findFirst()
                .orElseThrow(() -> new NoSuchElementException("记忆草稿不存在：" + draftId));

            MemoryType type =
                typeOverride != null ? typeOverride : MemoryType.parseOrDefault(found.type(), MemoryType.FEEDBACK);
            String title = titleOverride != null && !titleOverride.isBlank() ? titleOverride : found.title();
            String note = noteOverride != null && !noteOverride.isBlank() ? noteOverride : found.note();
            String why = whyOverride != null ? whyOverride : found.why();

            Entry saved = saveEntry(workspace, targetScope, null, type, title, note, why);
            drafts.removeIf(item -> draftId.equals(item.id()));
            if (drafts.isEmpty()) {
                deleteIfRegular(draftFile);
            } else {
                writeJson(draftFile, drafts);
            }
            return saved;
        }
    }

    public Entry saveEntry(Path workspace, String scope, String slugOrNull, MemoryType type, String title, String note,
        String why) {
        synchronized (lock()) {
            String actualScope = validateScope(scope);
            MemoryType actualType = type == null ? MemoryType.FEEDBACK : type;
            String cleanNote = validateText(note, "记忆正文", MAX_BODY_CHARS, false);
            String cleanWhy = validateOptionalText(why, "原因", MAX_WHY_CHARS, true);
            String cleanTitle = deriveTitle(title, cleanNote);

            Path dir = scopeDir(workspace, actualScope);
            Path topicsDir = dir.resolve(TOPICS_DIR);
            String slug;
            if (slugOrNull != null && !slugOrNull.isBlank()) {
                slug = slugOrNull.strip();
                validateSlug(slug);
                Path existing = topicsDir.resolve(slug + ".md");
                if (hasSymlinkUnderRoot(existing) || !Files.isRegularFile(existing, LinkOption.NOFOLLOW_LINKS)) {
                    throw new NoSuchElementException("记忆条目不存在：" + slug);
                }
            } else {
                ensureNotSymlink(topicsDir);
                slug = allocateUniqueSlug(topicsDir, cleanTitle, cleanNote);
            }

            String updated = LocalDate.now().toString();
            String description = oneLine(cleanNote, 120);
            String body = cleanWhy.isEmpty() ? cleanNote : cleanNote + "\n\nWhy: " + cleanWhy;
            Entry entry = new Entry(slug, actualScope, actualType.wire(), cleanTitle, description, body, updated);
            writeTopicFile(topicsDir.resolve(slug + ".md"), entry);
            rebuildIndex(dir, actualScope);
            return entry;
        }
    }

    public void deleteEntry(Path workspace, String scope, String slug) {
        synchronized (lock()) {
            String actualScope = validateScope(scope);
            validateSlug(slug);
            Path dir = scopeDir(workspace, actualScope);
            Path file = dir.resolve(TOPICS_DIR).resolve(slug + ".md");
            ensureNotSymlink(file);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new NoSuchElementException("记忆条目不存在：" + slug);
            }
            deleteIfRegular(file);
            rebuildIndex(dir, actualScope);
        }
    }

    public List<Entry> listEntries(Path workspace, String scope) {
        synchronized (lock()) {
            String actualScope = validateScope(scope);
            return readAllEntries(scopeDir(workspace, actualScope), actualScope);
        }
    }

    public int draftCount(Path workspace) {
        synchronized (lock()) {
            return readDrafts(projectDir(workspace).resolve(DRAFTS_FILE)).size();
        }
    }

    public List<Entry> searchEntries(Path workspace, String scopeOrNull, String query, MemoryType typeOrNull,
        int limit) {
        synchronized (lock()) {
            List<Entry> candidates = new ArrayList<>();
            if (scopeOrNull != null && !scopeOrNull.isBlank()) {
                String actualScope = validateScope(scopeOrNull);
                candidates.addAll(readAllEntries(scopeDir(workspace, actualScope), actualScope));
            } else {
                candidates.addAll(readAllEntries(scopeDir(workspace, SCOPE_PROJECT), SCOPE_PROJECT));
                candidates.addAll(readAllEntries(scopeDir(workspace, SCOPE_USER), SCOPE_USER));
            }
            String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
            int cap = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 50));
            return candidates.stream().filter(entry -> typeOrNull == null || typeOrNull.wire().equals(entry.type()))
                .filter(entry -> matchesQuery(entry, needle)).limit(cap).toList();
        }
    }

    public boolean hasLegacyDraftFile(Path workspace) {
        Path legacy = legacyDraftFile(workspace);
        if (legacy == null) {
            return false;
        }
        try {
            return !readBoundedUtf8(legacy, 4_096).isBlank();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 只读导入工作区遗留 {@code .pilot/memory-draft.md} 到外置候选区，绝不修改或删除目标仓库文件。 */
    public ImportResult importLegacyDraftFile(Path workspace) {
        synchronized (lock()) {
            Path legacy = legacyDraftFile(workspace);
            if (legacy == null) {
                return new ImportResult(0, 0, false);
            }
            String raw = readBoundedUtf8(legacy, MAX_LEGACY_DRAFT_BYTES);
            raw = raw.replace("\r\n", "\n").replace('\r', '\n');
            int imported = 0;
            int skipped = 0;
            for (String line : raw.split("\n")) {
                LegacyBullet parsed = parseLegacyBullet(line);
                if (parsed == null) {
                    continue;
                }
                int before = listDrafts(workspace).size();
                addDraft(workspace, parsed.type(), "", parsed.note(), parsed.why());
                if (listDrafts(workspace).size() > before) {
                    imported++;
                } else {
                    skipped++;
                }
            }
            return new ImportResult(imported, skipped, true);
        }
    }

    public Optional<Entry> readEntry(Path workspace, String scopeOrNull, String slug) {
        synchronized (lock()) {
            validateSlug(slug);
            if (scopeOrNull != null && !scopeOrNull.isBlank()) {
                String actualScope = validateScope(scopeOrNull);
                return readEntryFile(scopeDir(workspace, actualScope).resolve(TOPICS_DIR).resolve(slug + ".md"), slug,
                    actualScope);
            }
            Optional<Entry> project = readEntryFile(
                scopeDir(workspace, SCOPE_PROJECT).resolve(TOPICS_DIR).resolve(slug + ".md"), slug, SCOPE_PROJECT);
            if (project.isPresent()) {
                return project;
            }
            return readEntryFile(scopeDir(workspace, SCOPE_USER).resolve(TOPICS_DIR).resolve(slug + ".md"), slug,
                SCOPE_USER);
        }
    }

    public IndexView loadIndex(Path workspace, String scope) {
        synchronized (lock()) {
            String actualScope = validateScope(scope);
            Path dir = scopeDir(workspace, actualScope);
            Path indexFile = dir.resolve(INDEX_FILE);
            if (hasSymlinkUnderRoot(indexFile)) {
                return new IndexView("", false, 0);
            }
            if (!Files.isRegularFile(indexFile, LinkOption.NOFOLLOW_LINKS)) {
                List<Entry> entries = readAllEntries(dir, actualScope);
                if (entries.isEmpty()) {
                    return new IndexView("", false, 0);
                }
                rebuildIndex(dir, actualScope);
                if (hasSymlinkUnderRoot(indexFile) || !Files.isRegularFile(indexFile, LinkOption.NOFOLLOW_LINKS)) {
                    return new IndexView("", false, 0);
                }
            }
            String raw;
            try {
                raw = Files.readString(indexFile, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return new IndexView("", false, 0);
            }
            if (raw.startsWith("\uFEFF")) {
                raw = raw.substring(1);
            }
            raw = raw.replace("\r\n", "\n").replace('\r', '\n').strip();
            if (raw.isEmpty()) {
                return new IndexView("", false, 0);
            }
            int entryCount = (int)raw.lines().filter(line -> line.stripLeading().startsWith("- ")).count();
            return truncateIndex(raw, entryCount);
        }
    }

    public static String slugOf(String title, String note) {
        String seed = (title == null || title.isBlank()) ? (note == null ? "" : note) : title;
        String ascii = seed.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (ascii.isEmpty()) {
            return "mem-" + Sha256.hex(seed).substring(0, 10);
        }
        if (ascii.length() > 56) {
            ascii = ascii.substring(0, 56).replaceAll("-+$", "");
        }
        return ascii.isEmpty() ? "mem-" + Sha256.hex(seed).substring(0, 10) : ascii;
    }

    private Path scopeDir(Path workspace, String scope) {
        return SCOPE_USER.equals(scope) ? userDir() : projectDir(workspace);
    }

    private static boolean matchesQuery(Entry entry, String needle) {
        if (needle.isEmpty()) {
            return true;
        }
        return containsLower(entry.slug(), needle) || containsLower(entry.title(), needle)
            || containsLower(entry.description(), needle) || containsLower(entry.body(), needle);
    }

    private static boolean containsLower(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static Path legacyDraftFile(Path workspace) {
        Path base = (workspace == null ? Path.of(".") : workspace).toAbsolutePath().normalize();
        Path pilotDir = base.resolve(".pilot");
        if (Files.isSymbolicLink(pilotDir) || !Files.isDirectory(pilotDir, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        Path draftFile = pilotDir.resolve("memory-draft.md");
        if (Files.isSymbolicLink(draftFile) || !Files.isRegularFile(draftFile, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        return draftFile;
    }

    private static String readBoundedUtf8(Path file, int maxBytes) {
        try (InputStream stream = Files.newInputStream(file)) {
            return new String(stream.readNBytes(maxBytes), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取旧工作区记忆草稿失败: " + e.getMessage(), e);
        }
    }

    private record LegacyBullet(MemoryType type, String note, String why) {
    }

    private static LegacyBullet parseLegacyBullet(String rawLine) {
        String trimmed = rawLine == null ? "" : rawLine.strip();
        if (!trimmed.startsWith("- ")) {
            return null;
        }
        String text = trimmed.substring(2).strip();
        Matcher date = LEGACY_DATE_TAG.matcher(text);
        if (date.find()) {
            text = text.substring(date.end()).strip();
        }
        MemoryType type = MemoryType.FEEDBACK;
        Matcher typeMatcher = LEGACY_TYPE_TAG.matcher(text);
        if (typeMatcher.find()) {
            type = MemoryType.parseOrDefault(typeMatcher.group(1), MemoryType.FEEDBACK);
            text = text.substring(typeMatcher.end()).strip();
        }
        String why = "";
        int whyIndex = text.lastIndexOf(" (Why: ");
        if (whyIndex > 0 && text.endsWith(")")) {
            why = oneLine(text.substring(whyIndex + " (Why: ".length(), text.length() - 1), MAX_WHY_CHARS);
            text = text.substring(0, whyIndex).strip();
        }
        String note = oneLine(text, MAX_NOTE_CHARS);
        return note.isEmpty() ? null : new LegacyBullet(type, note, why);
    }

    private static Path resolveWorktreeRoot(Path worktreeDir, Path dotGit) {
        try {
            List<String> lines = Files.readAllLines(dotGit, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return null;
            }
            String first = lines.getFirst().strip();
            if (!first.startsWith("gitdir:")) {
                return null;
            }
            String rawGitDir = first.substring("gitdir:".length()).strip();
            if (rawGitDir.isEmpty()) {
                return null;
            }
            Path gitDir = Path.of(rawGitDir);
            gitDir = (gitDir.isAbsolute() ? gitDir : worktreeDir.resolve(gitDir)).normalize();
            Path commonDirFile = gitDir.resolve("commondir");
            if (Files.isRegularFile(commonDirFile, LinkOption.NOFOLLOW_LINKS)) {
                String common = Files.readString(commonDirFile, StandardCharsets.UTF_8).strip();
                if (!common.isEmpty()) {
                    Path commonPath = Path.of(common);
                    Path resolvedCommon =
                        (commonPath.isAbsolute() ? commonPath : gitDir.resolve(commonPath)).normalize();
                    if (".git".equals(fileName(resolvedCommon)) && resolvedCommon.getParent() != null) {
                        return realOrNormalized(resolvedCommon.getParent());
                    }
                    return realOrNormalized(resolvedCommon);
                }
            }
            Path parent = gitDir.getParent();
            if (parent != null && "worktrees".equals(fileName(parent)) && parent.getParent() != null) {
                Path maybeDotGit = parent.getParent();
                if (".git".equals(fileName(maybeDotGit)) && maybeDotGit.getParent() != null) {
                    return realOrNormalized(maybeDotGit.getParent());
                }
            }
            return realOrNormalized(worktreeDir);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? "" : name.toString();
    }

    private static Path realOrNormalized(Path path) {
        Path real = RealPaths.directory(path);
        return real != null ? real : path.toAbsolutePath().normalize();
    }

    private static String validateText(String raw, String label, int maxChars, boolean flatten) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        String text = flatten ? raw.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").strip()
            : raw.replace("\r\n", "\n").strip();
        if (text.length() > maxChars) {
            throw new IllegalArgumentException(label + "超过 " + maxChars + " 字符上限");
        }
        return text;
    }

    private static String validateOptionalText(String raw, String label, int maxChars, boolean flatten) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return validateText(raw, label, maxChars, flatten);
    }

    private static String deriveTitle(String title, String note) {
        if (title != null && !title.isBlank()) {
            return validateText(title, "记忆标题", MAX_TITLE_CHARS, true);
        }
        return oneLine(note, Math.min(60, MAX_TITLE_CHARS));
    }

    private static String oneLine(String text, int maxChars) {
        String line = text == null ? "" : text.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").strip();
        if (line.length() <= maxChars) {
            return line;
        }
        int end = Math.max(1, maxChars - 1);
        if (end > 0 && end < line.length() && Character.isHighSurrogate(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end) + "…";
    }

    private String allocateUniqueSlug(Path topicsDir, String title, String note) {
        String base = slugOf(title, note);
        validateSlug(base);
        if (!Files.exists(topicsDir.resolve(base + ".md"), LinkOption.NOFOLLOW_LINKS)) {
            return base;
        }
        for (int suffix = 2; suffix < 1_000; suffix++) {
            String tag = "-" + suffix;
            int prefixLen = Math.min(base.length(), 64 - tag.length());
            String candidate = base.substring(0, prefixLen).replaceAll("-+$", "") + tag;
            if (candidate.startsWith("-")) {
                candidate = "m" + candidate;
            }
            validateSlug(candidate);
            if (!Files.exists(topicsDir.resolve(candidate + ".md"), LinkOption.NOFOLLOW_LINKS)) {
                return candidate;
            }
        }
        return "mem-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private List<Draft> readDrafts(Path file) {
        ensureNotSymlink(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            if (raw.isBlank()) {
                return List.of();
            }
            List<Draft> loaded = MAPPER.readValue(raw, DRAFT_LIST);
            return loaded == null ? List.of() : List.copyOf(loaded);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("读取记忆候选区失败: " + e.getMessage(), e);
        }
    }

    private void writeJson(Path file, Object value) {
        ensureNotSymlink(file);
        try {
            writeAtomic(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("保存记忆候选区失败: " + e.getMessage(), e);
        }
    }

    private void writeTopicFile(Path file, Entry entry) {
        ensureNotSymlink(file);
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("name", entry.title());
        header.put("type", entry.type());
        header.put("description", entry.description());
        header.put("updated", entry.updated());
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setWidth(4096);
        String yaml = new Yaml(options).dump(header).stripTrailing();
        String content = "---\n" + yaml + "\n---\n\n" + entry.body().strip() + "\n";
        try {
            writeAtomic(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException("保存记忆条目失败: " + e.getMessage(), e);
        }
    }

    private void rebuildIndex(Path scopeDir, String scope) {
        Path indexFile = scopeDir.resolve(INDEX_FILE);
        ensureNotSymlink(indexFile);
        List<Entry> entries = readAllEntries(scopeDir, scope);
        if (entries.isEmpty()) {
            deleteIfRegular(indexFile);
            return;
        }
        StringBuilder out = new StringBuilder(INDEX_HEADER);
        out.append('\n');
        for (Entry entry : entries) {
            String line = formatIndexLine(entry);
            out.append(line).append('\n');
        }
        try {
            writeAtomic(indexFile, out.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("更新记忆索引失败: " + e.getMessage(), e);
        }
    }

    private static String formatIndexLine(Entry entry) {
        String title = oneLine(entry.title(), 60);
        String desc = oneLine(entry.description(), 100);
        String detail = desc.isEmpty() || desc.equals(title) ? title : title + " — " + desc;
        String line = "- [" + entry.slug() + "] (" + entry.type() + ") " + detail;
        if (line.length() <= MAX_INDEX_ENTRY_CHARS) {
            return line;
        }
        int cut = MAX_INDEX_ENTRY_CHARS - 1;
        if (Character.isHighSurrogate(line.charAt(cut - 1))) {
            cut--;
        }
        return line.substring(0, cut) + "…";
    }

    private List<Entry> readAllEntries(Path scopeDir, String scope) {
        Path topicsDir = scopeDir.resolve(TOPICS_DIR);
        if (hasSymlinkUnderRoot(topicsDir) || !Files.isDirectory(topicsDir, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(topicsDir)) {
            files = stream.filter(path -> !Files.isSymbolicLink(path))
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .filter(path -> path.getFileName().toString().endsWith(".md")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("扫描记忆条目失败: " + e.getMessage(), e);
        }
        List<Entry> entries = new ArrayList<>();
        for (Path file : files) {
            String filename = file.getFileName().toString();
            String slug = filename.substring(0, filename.length() - ".md".length());
            if (!SLUG_PATTERN.matcher(slug).matches()) {
                continue;
            }
            readEntryFile(file, slug, scope).ifPresent(entries::add);
        }
        entries.sort(Comparator.comparing(Entry::updated, Comparator.reverseOrder()).thenComparing(Entry::slug));
        return List.copyOf(entries);
    }

    private Optional<Entry> readEntryFile(Path file, String slug, String scope) {
        if (hasSymlinkUnderRoot(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        String raw;
        try {
            raw = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Optional.empty();
        }
        if (raw.startsWith("\uFEFF")) {
            raw = raw.substring(1);
        }
        List<String> lines = raw.lines().toList();
        if (lines.isEmpty() || !"---".equals(lines.getFirst().strip())) {
            String body = boundedBody(raw.strip());
            return body.isEmpty() ? Optional.empty()
                : Optional.of(new Entry(slug, scope, MemoryType.FEEDBACK.wire(), slug, oneLine(body, 120), body, ""));
        }
        int end = -1;
        for (int i = 1; i < lines.size(); i++) {
            if ("---".equals(lines.get(i).strip())) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            return Optional.empty();
        }
        String headerText = String.join("\n", lines.subList(1, end));
        if (headerText.length() > MAX_FRONTMATTER_CHARS) {
            return Optional.empty();
        }
        Map<?, ?> values = parseYaml(headerText);
        String name = stringField(values.get("name"), slug);
        MemoryType type = parseTypeLenient(stringField(values.get("type"), ""));
        String description = stringField(values.get("description"), "");
        String updated = formatDateField(values.get("updated"));
        String body = boundedBody(String.join("\n", lines.subList(end + 1, lines.size())).strip());
        if (description.isEmpty()) {
            description = oneLine(body, 120);
        }
        return Optional.of(new Entry(slug, scope, type.wire(), name, description, body, updated));
    }

    private static Map<?, ?> parseYaml(String headerText) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(20);
        options.setNestingDepthLimit(20);
        options.setCodePointLimit(MAX_FRONTMATTER_CHARS);
        try {
            Object loaded = new Yaml(new SafeConstructor(options)).load(headerText);
            return loaded instanceof Map<?, ?> map ? map : Map.of();
        } catch (YAMLException e) {
            return Map.of();
        }
    }

    private static MemoryType parseTypeLenient(String raw) {
        if (raw == null || raw.isBlank()) {
            return MemoryType.FEEDBACK;
        }
        try {
            return MemoryType.parse(raw);
        } catch (IllegalArgumentException e) {
            return MemoryType.FEEDBACK;
        }
    }

    private static String stringField(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).strip();
        return text.isEmpty() ? fallback : text;
    }

    private static String formatDateField(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Date date) {
            return date.toInstant().atZone(ZoneOffset.UTC).toLocalDate().toString();
        }
        return String.valueOf(value).strip();
    }

    private static String boundedBody(String body) {
        if (body.length() <= MAX_BODY_CHARS) {
            return body;
        }
        int end = MAX_BODY_CHARS;
        if (Character.isHighSurrogate(body.charAt(end - 1))) {
            end--;
        }
        return body.substring(0, end) + "\n…（记忆正文过长，已截断）";
    }

    private static IndexView truncateIndex(String raw, int entryCount) {
        String[] lines = raw.split("\n", -1);
        boolean truncated = false;
        String text = raw;
        if (lines.length > MAX_INDEX_LINES) {
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < MAX_INDEX_LINES; i++) {
                if (i > 0) {
                    joined.append('\n');
                }
                joined.append(lines[i]);
            }
            text = joined.toString();
            truncated = true;
        }
        if (text.length() > MAX_INDEX_CHARS) {
            int end = MAX_INDEX_CHARS;
            if (Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            text = text.substring(0, end);
            truncated = true;
        }
        if (truncated) {
            text = text.stripTrailing() + "\n…（记忆索引超出上限，已截断）";
        }
        return new IndexView(text, truncated, entryCount);
    }

    private boolean hasSymlinkUnderRoot(Path target) {
        for (Path current = target; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) {
                return true;
            }
            if (current.equals(root)) {
                break;
            }
        }
        return false;
    }

    private void ensureNotSymlink(Path target) {
        for (Path current = target; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("拒绝访问符号链接记忆路径：" + current.getFileName());
            }
            if (current.equals(root)) {
                break;
            }
        }
    }

    private static void deleteIfRegular(Path file) {
        if (Files.isSymbolicLink(file)) {
            throw new IllegalArgumentException("拒绝删除符号链接：" + file.getFileName());
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("删除记忆文件失败: " + e.getMessage(), e);
        }
    }

    private static void writeAtomic(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Path staging = Files.createTempFile(file.getParent(), ".memory-", ".tmp");
        try {
            Files.writeString(staging, content, StandardCharsets.UTF_8);
            try {
                Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staging);
        }
    }
}
