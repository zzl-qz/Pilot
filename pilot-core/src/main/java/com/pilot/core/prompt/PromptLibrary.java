package com.pilot.core.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词仓库：喂给模型的指令性文本放在 {@code prompts/} 目录里维护，Java 代码只引用名字。
 *
 * <p>目录结构（classpath 与外部目录同名，外部优先）：
 * <pre>
 * prompts/system/base.md            身份、边界、意图判定
 * prompts/system/workflow.md        调研、计划、编辑、验证
 * prompts/system/tools.md           工具选择策略（跨工具约束）
 * prompts/system/tools-agents.md    子代理选择行（frontmatter 声明 requires）
 * prompts/system/orchestration.md   证据与风险（对单 Agent 也成立的条款）
 * prompts/system/dispatch.md        多智能体调度（requires: dispatch_agents）
 * prompts/system/output.md          结论格式
 * prompts/system/repository.md      仓库快照（顶层结构 / git / 构建方式）
 * prompts/system/project-rules.md   项目约定（PILOT.md 等）
 * prompts/system/plan-context.md    计划注入（{{planItems}}）
 * prompts/system/plan-done.md       计划已全部完成时的提醒
 * prompts/tools/&lt;name&gt;.md           每个工具的描述，名字必须与 Tool.name() 一致
 * prompts/completion/verify.md      收尾前的验证要求
 * prompts/completion/review.md      有改动时的收尾自审
 * prompts/completion/review-readonly.md  只读调研任务的自审
 * prompts/compact/summary.md        上下文压缩摘要
 * prompts/plan/continue.md          计划未完成时的自动继续（{{openSteps}}）
 * prompts/subagent/task.md          子代理任务说明（{{task}}）
 * prompts/subagent/base.md          子 Agent 系统提示（{{taskId}} / {{scope}} / {{risk}}）
 * prompts/subagent/job.md           子 Agent 的具体任务（{{title}} / {{goal}} / {{note}}）
 * </pre>
 *
 * <p>system 段落可以在文件头声明 frontmatter（首行 {@code ---}，{@code requires:} 一键，再一行
 * {@code ---}），声明该段依赖的工具，如 {@code requires: [dispatch_agents]}；组装时这些工具
 * 不在可用集合里，整段跳过——工具未注册，用法教学就不该出现在提示词里。
 * frontmatter 只支持 {@code requires} 一个键，出现其他键或格式非法时抛异常：
 * 与变量缺失同一哲学，破损的提示词不应该被静默送进模型。
 *
 * <p>模板变量写作 {@code {{name}}}，渲染时缺失即抛异常（宁可当场失败，也不要给模型一段
 * 带占位符的破损提示词）。classpath 资源不可变，读一次缓存；外部目录每次读盘，
 * 因此调提示词不需要重新打包，只要重启服务或等下一次运行。
 *
 * <p>外部目录通过系统属性 {@code pilot.prompts.dir} 或环境变量 {@code PILOT_PROMPTS_DIR} 指定。
 * 只覆盖个别文件也可以：缺失的文件回落到 classpath 版本。
 */
public final class PromptLibrary {

    public static final String DIR_PROPERTY = "pilot.prompts.dir";
    public static final String DIR_ENV = "PILOT_PROMPTS_DIR";

    /**
     * 系统提示词注册表：按 order 稀疏分配拼接顺序，requiresTools 声明依赖
     * （代码声明优先于 md 头部 frontmatter）。新增段落取区间空隙值。
     */
    private static final List<SystemSection> SYSTEM_SECTIONS = List.of(
            new SystemSection("system/base", 0),
            new SystemSection("system/workflow", 100),
            new SystemSection("system/tools", 200),
            new SystemSection("system/tools-agents", 300, Set.of("spawn_agent", "dispatch_agents")),
            new SystemSection("system/orchestration", 400),
            new SystemSection("system/dispatch", 500, Set.of("dispatch_agents")),
            new SystemSection("system/output", 9000));

    /** frontmatter 解析结果：声明的依赖工具 + 去掉声明头之后的正文。 */
    private record Parsed(Set<String> requires, String body) {
    }

    private static final String CLASSPATH_ROOT = "prompts/";
    private static final String SUFFIX = ".md";
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");

    private static volatile PromptLibrary standard;

    private final Path externalDir;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /** @param externalDir 外部提示词目录，{@code null} 表示只用 classpath */
    public PromptLibrary(Path externalDir) {
        this.externalDir = externalDir;
    }

    /** 只用打包进来的提示词。 */
    public static PromptLibrary classpathOnly() {
        return new PromptLibrary(null);
    }

    /** 进程内共享实例：外部目录配置只在第一次调用时解析一次。 */
    public static PromptLibrary standard() {
        PromptLibrary current = standard;
        if (current == null) {
            synchronized (PromptLibrary.class) {
                current = standard;
                if (current == null) {
                    current = new PromptLibrary(configuredDir());
                    standard = current;
                }
            }
        }
        return current;
    }

    /** 拼出完整系统提示词：各段用空行隔开，去掉每段首尾空白。不过滤依赖工具的段。 */
    public String system(Map<String, String> variables) {
        return system(variables, null);
    }

    /**
     * 拼出完整系统提示词，并按实际可用工具过滤段落。
     *
     * <p>段落顺序由注册表的 order 决定（越动态的段越靠后）；依赖声明取注册表与 frontmatter
     * 中的前者：代码声明优先，没有时回落到 md 标记。
     *
     * @param availableTools 本轮会话实际注册的工具名；{@code null} 表示不过滤（全部段落注入）。
     *                       声明了依赖且其中任一工具不在集合里时，整段跳过。
     */
    public String system(Map<String, String> variables, Set<String> availableTools) {
        List<SystemSection> ordered = SYSTEM_SECTIONS.stream()
                .sorted(Comparator.comparingInt(SystemSection::order))
                .toList();
        List<String> sections = new ArrayList<>(ordered.size());
        for (SystemSection section : ordered) {
            Parsed parsed = parseFrontmatter(section.name(), get(section.name()));
            Set<String> requires = !section.requiresTools().isEmpty()
                    ? section.requiresTools()
                    : parsed.requires();
            if (availableTools != null && !requires.isEmpty() && !availableTools.containsAll(requires)) {
                continue;
            }
            String text = interpolate(section.name(), parsed.body(), variables).strip();
            if (!text.isEmpty()) {
                sections.add(text);
            }
        }
        return String.join("\n\n", sections);
    }

    /** 单段渲染，用于 plan / subagent 这类运行时拼接的片段；头部 frontmatter 不进正文。 */
    public String render(String name, Map<String, String> variables) {
        Parsed parsed = parseFrontmatter(name, get(name));
        return interpolate(name, parsed.body(), variables);
    }

    private String interpolate(String name, String template, Map<String, String> variables) {
        Matcher matcher = VARIABLE.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 64);
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = variables == null ? null : variables.get(key);
            if (value == null) {
                throw new IllegalArgumentException(
                        "提示词 " + name + " 引用了未提供的变量 {{" + key + "}}；已提供：" + keys(variables));
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * 解析段落头部的 frontmatter：首行 {@code ---}，若干 {@code key: value}，再一行 {@code ---}。
     *
     * <p>只支持 {@code requires} 一个键，值为逗号分隔或方括号包裹的工具名列表；
     * 没有 frontmatter 时原文即正文。格式非法一律抛异常，不静默降级。
     */
    static Parsed parseFrontmatter(String name, String template) {
        String text = template == null ? "" : template;
        List<String> lines = text.lines().toList();
        if (lines.isEmpty() || !"---".equals(lines.get(0).strip())) {
            return new Parsed(Set.of(), text);
        }
        int end = -1;
        for (int i = 1; i < lines.size(); i++) {
            if ("---".equals(lines.get(i).strip())) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            throw new IllegalArgumentException("提示词 " + name + " 的 frontmatter 缺少结束分隔符 ---");
        }
        Set<String> requires = new LinkedHashSet<>();
        for (int i = 1; i < end; i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("提示词 " + name + " 的 frontmatter 行无法解析：" + line);
            }
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            if (!"requires".equals(key)) {
                throw new IllegalArgumentException("提示词 " + name
                        + " 的 frontmatter 不支持的键：" + key + "（只支持 requires）");
            }
            requires.addAll(toolList(value));
        }
        String body = String.join("\n", lines.subList(end + 1, lines.size()));
        return new Parsed(Set.copyOf(requires), body);
    }

    /** {@code [a, b]} / {@code a, b} / {@code a} 三种写法统一成工具名列表。 */
    private static List<String> toolList(String value) {
        String list = value.strip();
        if (list.startsWith("[") && list.endsWith("]")) {
            list = list.substring(1, list.length() - 1);
        } else if (list.startsWith("[") != list.endsWith("]")) {
            throw new IllegalArgumentException("frontmatter requires 列表括号不成对：" + value);
        }
        return Arrays.stream(list.split("[,，]"))
                .map(String::strip)
                .filter(item -> !item.isEmpty())
                .toList();
    }

    /** 读取模板原文，不做变量替换。 */
    public String get(String name) {
        requireValidName(name);
        if (externalDir != null) {
            Path file = externalDir.resolve(name + SUFFIX);
            if (Files.isRegularFile(file)) {
                return readFile(file);
            }
        }
        return cache.computeIfAbsent(name, this::readClasspath);
    }

    /** 系统提示词常用的环境变量，调用方可以在此基础上追加自己需要的键。 */
    public static Map<String, String> environment(String workspace) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("platform", platform());
        variables.put("today", LocalDate.now().toString());
        variables.put("workspace", workspaceName(workspace));
        return Map.copyOf(variables);
    }

    private static String platform() {
        String os = System.getProperty("os.name", "unknown").strip();
        String arch = System.getProperty("os.arch", "").strip();
        return arch.isEmpty() ? os : os + " / " + arch;
    }

    private static String workspaceName(String workspace) {
        if (workspace == null || workspace.isBlank()) {
            return "当前工作区";
        }
        try {
            Path fileName = Path.of(workspace).normalize().getFileName();
            return fileName == null ? workspace.strip() : fileName.toString();
        } catch (RuntimeException e) {
            return workspace.strip();
        }
    }

    private static String keys(Map<String, String> variables) {
        return variables == null || variables.isEmpty() ? "（空）" : String.join(", ", variables.keySet());
    }

    private static void requireValidName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("提示词名字不能为空");
        }
        if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
            throw new IllegalArgumentException("非法的提示词名字：" + name);
        }
    }

    private String readClasspath(String name) {
        String resource = CLASSPATH_ROOT + name + SUFFIX;
        try (InputStream in = PromptLibrary.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("找不到提示词资源 " + resource
                        + "；确认它位于 pilot-core/src/main/resources/prompts 并且已打包");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取提示词资源失败：" + resource, e);
        }
    }

    private static String readFile(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取提示词文件失败：" + file, e);
        }
    }

    private static Path configuredDir() {
        String configured = System.getProperty(DIR_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(DIR_ENV);
        }
        if (configured == null || configured.isBlank()) {
            return null;
        }
        Path dir = Path.of(configured.strip()).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("提示词目录不存在：" + dir
                    + "（来自系统属性 " + DIR_PROPERTY + " 或环境变量 " + DIR_ENV + "）");
        }
        return dir;
    }
}
