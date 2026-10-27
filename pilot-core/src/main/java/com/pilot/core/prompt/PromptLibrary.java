package com.pilot.core.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * prompts/system/tools.md           工具选择策略
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
 * </pre>
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

    /** 系统提示词的组装顺序：身份 → 流程 → 工具 → 输出。 */
    private static final List<String> SYSTEM_SECTIONS =
            List.of("system/base", "system/workflow", "system/tools", "system/output");

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

    /** 拼出完整系统提示词：各段用空行隔开，去掉每段首尾空白。 */
    public String system(Map<String, String> variables) {
        List<String> sections = new ArrayList<>(SYSTEM_SECTIONS.size());
        for (String name : SYSTEM_SECTIONS) {
            String text = render(name, variables).strip();
            if (!text.isEmpty()) {
                sections.add(text);
            }
        }
        return String.join("\n\n", sections);
    }

    /** 单段渲染，用于 plan / subagent 这类运行时拼接的片段。 */
    public String render(String name, Map<String, String> variables) {
        String template = get(name);
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
