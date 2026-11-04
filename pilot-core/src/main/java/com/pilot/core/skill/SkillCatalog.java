package com.pilot.core.skill;

import java.io.IOException;
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
    public static final int MAX_BODY_LINES = 500;
    public static final long MAX_BODY_BYTES = 48L * 1024;
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
     * 扫描技能目录。
     *
     * @param roots 按优先级降序的扫描来源（项目级在前）；不存在的目录跳过
     */
    public static SkillCatalog scan(List<Root> roots) {
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
                load(dir, root.source(), byName, commands, problems);
            }
        }
        return new SkillCatalog(ordered(byName), List.copyOf(problems));
    }

    /** 按加载序固化：项目级遮蔽用户级的语义依赖插入顺序，Map.copyOf 不保序，先按名重排。 */
    private static Map<String, Skill> ordered(Map<String, Skill> byName) {
        Map<String, Skill> sorted = new LinkedHashMap<>();
        byName.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted;
    }

    private static void load(Path dir, SkillSource source, Map<String, Skill> byName, Set<String> commands,
        List<SkillProblem> problems) {
        String dirName = dir.getFileName().toString();
        Path skillMd = dir.resolve("SKILL.md");
        if (!Files.isRegularFile(skillMd)) {
            return; // 没有 SKILL.md 的目录不是技能（可能是任何无关目录），静默跳过
        }
        // 同名技能已被更高优先级来源加载：遮蔽是设计行为，不亮牌
        if (byName.containsKey(dirName)) {
            return;
        }
        if (byName.size() >= MAX_SKILLS) {
            problems.add(new SkillProblem(dirName, dir.toString(), "有效技能数已达上限 " + MAX_SKILLS + "，本技能被跳过"));
            return;
        }
        if (!VALID_NAME.matcher(dirName).matches()) {
            problems.add(new SkillProblem(dirName, dir.toString(), "目录名必须匹配 ^[a-z][a-z0-9-]{0,63}$"));
            return;
        }
        String text;
        try {
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
        List<String> files = listFiles(dir);
        byName.put(dirName, new Skill(new SkillManifest(dirName, parsed.description(), parsed.command(),
            parsed.argumentHint(), parsed.allowedTools(), dir, source), parsed.body(), files));
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
        Path root = skill.manifest().directory();
        String cleaned = file == null ? "" : file.strip().replace('\\', '/');
        if (cleaned.isEmpty() || cleaned.startsWith("/") || cleaned.contains("../")) {
            throw new IllegalArgumentException("非法的文件路径：" + file);
        }
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
        if (isBinary(raw)) {
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
    private static boolean isBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, 8000);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }
}
