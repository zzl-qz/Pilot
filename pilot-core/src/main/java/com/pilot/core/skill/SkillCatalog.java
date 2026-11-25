package com.pilot.core.skill;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.pilot.core.contract.Contracts;
import com.pilot.core.util.Texts;

/**
 * 技能目录：扫描两级来源（项目级在前、用户级在后，roots 按优先级降序传入）并解析 SKILL.md。
 *
 * <p>同名技能靠前的来源遮蔽靠后的（项目级 &gt; 用户级）；每次调用重新扫描、不缓存，
 * 改技能文件随下次运行生效——与 {@code PromptLibrary} 外部目录的热更新行为一致。
 *
 * <p>无效技能 fail-closed（不进目录、不可 {@code use_skill}）+ fail-visible
 * （{@link #invalid()} 亮牌）：一个坏技能只影响自己，绝不中断会话。
 * 校验上限的动机都是保护上下文——目录段按技能数付费、正文按行数付费。
 */
public final class SkillCatalog {

    /** 目录段的 token 上限保护：超过后其余技能跳过并亮牌。 */
    public static final int MAX_SKILLS = 32;
    /** SKILL.md 正文上限：激活一次最多注入这么多行。 */
    public static final int MAX_BODY_LINES = 2000;
    public static final long MAX_BODY_BYTES = 128L * 1024;
    /** 技能目录内文件清单与单文件读取上限，对齐 read_file 的单次行数。 */
    public static final int MAX_FILES = 200;
    public static final int MAX_FILE_LINES = 2000;
    private static final long MAX_FILE_BYTES = 1024L * 1024;
    private static final Pattern VALID_NAME = Pattern.compile("^[a-z][a-z0-9-]{0,63}$");

    /** 无技能时的共享空目录（enabled=false 或两级目录都不存在）。 */
    public static final SkillCatalog EMPTY = new SkillCatalog(Map.of(), List.of());

    /** 一个扫描来源：目录 + 层级。roots 按优先级降序传入（项目级在前）。 */
    public record Root(Path directory, SkillSource source) {
        public Root {
            directory = directory.toAbsolutePath().normalize();
        }
    }

    private final Map<String, Skill> skills;
    private final List<SkillProblem> invalid;

    private SkillCatalog(Map<String, Skill> skills, List<SkillProblem> invalid) {
        this.skills = skills;
        this.invalid = invalid;
    }

    /**
     * 扫描技能目录（不含内置技能，保持单目录/自定义扫描语义稳定）。
     *
     * @param roots 按优先级降序的扫描来源（项目级在前）；不存在的目录跳过
     */
    public static SkillCatalog scan(List<Root> roots) {
        return scan(roots, MAX_SKILLS, false);
    }

    /**
     * 扫描技能目录，并可选附带 classpath 内置开箱技能（优先级：项目级 &gt; 用户级 &gt; 内置）。
     */
    public static SkillCatalog scan(List<Root> roots, boolean includeBuiltins) {
        return scan(roots, MAX_SKILLS, includeBuiltins);
    }

    /** 安装候选不受模型常驻目录的数量上限影响；下载器负责候选数量和体积预算。 */
    public static SkillCatalog installationCandidates(Path root) {
        return scan(List.of(new Root(root, SkillSource.PROJECT)), Integer.MAX_VALUE, false);
    }

    /** 单技能仓库的名称来自声明，不要求仓库名与技能名相同。 */
    public static String installationName(Path directory) {
        try {
            Path file = directory.resolve("SKILL.md");
            if (Files.size(file) > MAX_BODY_BYTES + 64 * 1024) {
                throw new IllegalArgumentException("SKILL.md 过大");
            }
            String declared = SkillFrontmatter.parse(Files.readString(file, StandardCharsets.UTF_8)).name();
            return declared == null ? directory.getFileName().toString() : declared;
        } catch (IOException | IllegalArgumentException failure) {
            return directory.getFileName().toString();
        }
    }

    private static SkillCatalog scan(List<Root> roots, int maxSkills, boolean includeBuiltins) {
        Map<String, Skill> byName = new LinkedHashMap<>();
        List<SkillProblem> problems = new ArrayList<>();
        Set<String> commands = new LinkedHashSet<>();
        for (Root root : roots) {
            if (root == null || !Files.isDirectory(root.directory())) {
                continue;
            }
            List<Path> dirs;
            try (Stream<Path> entries = Files.list(root.directory())) {
                dirs = entries.filter(Files::isDirectory).filter(path -> !Files.isSymbolicLink(path))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
            } catch (IOException e) {
                problems
                    .add(new SkillProblem("（目录）", root.directory().toString(), "扫描失败：" + e.getClass().getSimpleName()));
                continue;
            }
            for (Path dir : dirs) {
                load(dir, root.source(), byName, commands, problems, maxSkills);
            }
        }
        if (includeBuiltins) {
            loadBuiltins(byName, commands, maxSkills);
        }
        return new SkillCatalog(ordered(byName), List.copyOf(problems));
    }

    /** 从 classpath {@code skills/index.txt} 加载内置技能包；同名项目/用户技能静默覆盖，快捷命令冲突时让位给外部技能。 */
    private static void loadBuiltins(Map<String, Skill> byName, Set<String> commands, int maxSkills) {
        ClassLoader loader = SkillCatalog.class.getClassLoader();
        String indexText = readClasspathText(loader, "skills/index.txt");
        if (indexText == null || indexText.isBlank()) {
            return;
        }
        for (String rawLine : indexText.lines().toList()) {
            String slug = rawLine.strip();
            if (slug.isEmpty() || slug.startsWith("#") || !VALID_NAME.matcher(slug).matches()) {
                continue;
            }
            if (byName.containsKey(slug) || byName.size() >= maxSkills) {
                continue;
            }
            String content = readClasspathText(loader, "skills/" + slug + "/SKILL.md");
            if (content == null || content.isBlank()) {
                continue;
            }
            SkillFrontmatter.Parsed parsed;
            try {
                parsed = SkillFrontmatter.parse(content);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if ((parsed.name() != null && !parsed.name().equals(slug)) || parsed.body().isEmpty()) {
                continue;
            }
            Contracts.Check required = Contracts.check(parsed.requires());
            if (!required.satisfied()) {
                continue;
            }
            String command = parsed.command();
            if (command != null && !commands.add(command)) {
                // 项目/用户技能优先占用同名快捷指令，内置技能保留本身但让出快捷命令
                command = null;
            }
            Path virtualDir = Path.of("builtin", slug);
            byName.put(slug,
                new Skill(
                    new SkillManifest(slug, parsed.description(), command, parsed.argumentHint(), parsed.allowedTools(),
                        parsed.requires(), parsed.requiresOptional(), virtualDir, SkillSource.BUILTIN),
                    parsed.body(), List.of()));
        }
    }

    private static String readClasspathText(ClassLoader loader, String resource) {
        try (InputStream stream = loader.getResourceAsStream(resource)) {
            if (stream == null) {
                return null;
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** 按加载序固化：项目级遮蔽用户级的语义依赖插入顺序，Map.copyOf 不保序，先按名重排。 */
    private static Map<String, Skill> ordered(Map<String, Skill> byName) {
        Map<String, Skill> sorted = new LinkedHashMap<>();
        byName.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted;
    }

    private static void load(Path dir, SkillSource source, Map<String, Skill> byName, Set<String> commands,
        List<SkillProblem> problems, int maxSkills) {
        String dirName = dir.getFileName().toString();
        Path skillMd = dir.resolve("SKILL.md");
        if (!Files.isRegularFile(skillMd)) {
            return; // 没有 SKILL.md 的目录不是技能（可能是任何无关目录），静默跳过
        }
        // 同名技能已被更高优先级来源加载：遮蔽是设计行为，不亮牌
        if (byName.containsKey(dirName)) {
            return;
        }
        if (byName.size() >= maxSkills) {
            problems.add(new SkillProblem(dirName, dir.toString(), "有效技能数已达上限 " + MAX_SKILLS + "，本技能被跳过"));
            return;
        }
        if (!VALID_NAME.matcher(dirName).matches()) {
            problems.add(new SkillProblem(dirName, dir.toString(), "目录名必须匹配 ^[a-z][a-z0-9-]{0,63}$"));
            return;
        }
        String text;
        try {
            if (Files.size(skillMd) > MAX_BODY_BYTES + 64 * 1024) {
                problems.add(new SkillProblem(dirName, dir.toString(), "SKILL.md 文件超过读取上限 192KB"));
                return;
            }
            text = Files.readString(skillMd, StandardCharsets.UTF_8);
        } catch (IOException e) {
            problems.add(new SkillProblem(dirName, dir.toString(), "读取 SKILL.md 失败：" + e.getClass().getSimpleName()));
            return;
        }
        SkillFrontmatter.Parsed parsed;
        try {
            parsed = SkillFrontmatter.parse(text);
        } catch (IllegalArgumentException e) {
            problems.add(new SkillProblem(dirName, dir.toString(), e.getMessage()));
            return;
        }
        if (parsed.name() != null && !parsed.name().equals(dirName)) {
            problems.add(new SkillProblem(dirName, dir.toString(), "name 声明（" + parsed.name() + "）与目录名不一致；目录名是真源名"));
            return;
        }
        if (parsed.body().isEmpty()) {
            problems.add(new SkillProblem(dirName, dir.toString(), "正文为空：SKILL.md 只有 frontmatter，没有可执行的指引"));
            return;
        }
        if (parsed.body().lines().count() > MAX_BODY_LINES
            || parsed.body().getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            problems.add(new SkillProblem(dirName, dir.toString(),
                "正文超过上限（" + MAX_BODY_LINES + " 行 / " + (MAX_BODY_BYTES / 1024) + "KB）"));
            return;
        }
        if (parsed.command() != null && !commands.add(parsed.command())) {
            problems.add(new SkillProblem(dirName, dir.toString(), "快捷指令 " + parsed.command() + " 与已有技能冲突，本技能不可用"));
            return;
        }
        // 缺必需契约就不加载，并把原因说出来：静默少一个技能比直接报错更难查。
        Contracts.Check required = Contracts.check(parsed.requires());
        if (!required.satisfied()) {
            problems.add(new SkillProblem(dirName, dir.toString(), "缺少必需契约，未加载：" + required.describe()));
            return;
        }
        List<String> files = listFiles(dir);
        byName.put(dirName,
            new Skill(
                new SkillManifest(dirName, parsed.description(), parsed.command(), parsed.argumentHint(),
                    parsed.allowedTools(), parsed.requires(), parsed.requiresOptional(), dir, source),
                parsed.body(), files));
    }

    /**
     * 递归列出技能目录内的文件（排除 SKILL.md 与 {@code .git/} 下的一切），
     * 路径统一用 {@code /} 分隔；上限 MAX_FILES。
     *
     * <p>跳过 {@code .git}：手动把 git 仓库 clone 进技能目录是合法安装方式，
     * 但仓库元数据对 use_skill 的文件清单只有噪音（还能把 200 条配额吃光），
     * 参考文件与脚本才是渐进披露第三层关心的内容。
     */
    private static List<String> listFiles(Path dir) {
        List<String> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir, 6)) {
            walk.filter(Files::isRegularFile).filter(path -> !path.getFileName().toString().equals("SKILL.md"))
                .filter(path -> !insideGit(dir, path)).limit(MAX_FILES)
                .forEach(path -> files.add(dir.relativize(path).toString().replace('\\', '/')));
        } catch (IOException e) {
            return List.of();
        }
        files.sort(String::compareTo);
        return List.copyOf(files);
    }

    /** 相对路径里任一段是 {@code .git} 即命中（含 {@code .git} 里的文件与其深层子目录）。 */
    private static boolean insideGit(Path dir, Path path) {
        for (int i = dir.getNameCount(); i < path.getNameCount(); i++) {
            if (".git".equals(path.getName(i).toString())) {
                return true;
            }
        }
        return false;
    }

    /** 按名取技能，含正文与文件清单。 */
    public Optional<Skill> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(skills.get(name.strip()));
    }

    /** 有效技能清单，按名排序。 */
    public List<SkillManifest> list() {
        return skills.values().stream().map(Skill::manifest).sorted(Comparator.comparing(SkillManifest::name)).toList();
    }

    /** 校验失败清单：名称、路径、原因，供 API 展示与日志。 */
    public List<SkillProblem> invalid() {
        return invalid;
    }

    /**
     * 读取技能目录内的文本文件（渐进披露第三层）。
     *
     * @throws IllegalArgumentException 路径越出技能目录（含符号链接逃逸）、文件不存在、
     *                                  疑似二进制、读取失败；调用方把原因转成工具错误信息
     */
    public String readFile(Skill skill, String file) {
        String cleaned = file == null ? "" : file.strip().replace('\\', '/');
        if (cleaned.isEmpty() || cleaned.startsWith("/") || cleaned.contains("../") || cleaned.equals("..")) {
            throw new IllegalArgumentException("非法的文件路径：" + file);
        }
        if (skill.manifest().source() == SkillSource.BUILTIN) {
            String resource = "skills/" + skill.manifest().name() + "/" + cleaned;
            String text = readClasspathText(SkillCatalog.class.getClassLoader(), resource);
            if (text == null) {
                throw new IllegalArgumentException("文件不存在或无法访问：" + cleaned);
            }
            return text;
        }
        Path root = skill.manifest().directory();
        Path target = root.resolve(cleaned).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("文件路径越出技能目录：" + file);
        }
        try {
            // 符号链接可能指向技能目录之外，与 PathValidator 同思路做 realpath 复核
            if (!target.toRealPath().startsWith(root.toRealPath())) {
                throw new IllegalArgumentException("文件路径越出技能目录（符号链接）：" + file);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("文件不存在或无法访问：" + file);
        }
        byte[] raw;
        try {
            raw = Files.readAllBytes(target);
        } catch (IOException | OutOfMemoryError e) {
            throw new IllegalArgumentException("读取文件失败：" + cleaned);
        }
        if (Texts.isBinary(raw)) {
            throw new IllegalArgumentException("疑似二进制文件，不进入提示词：" + cleaned);
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        List<String> lines = text.lines().toList();
        if (lines.size() > MAX_FILE_LINES || raw.length > MAX_FILE_BYTES) {
            return String.join("\n", lines.subList(0, Math.min(lines.size(), MAX_FILE_LINES))) + "\n…（超出 "
                + MAX_FILE_LINES + " 行上限，已截断）";
        }
        return text;
    }

    /** 采样前 8KB 是否含 NUL 字节，与 read_file 的二进制判断同款。 */
}
