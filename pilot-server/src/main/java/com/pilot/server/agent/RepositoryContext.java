package com.pilot.server.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.pilot.core.lint.ProjectLint;
import com.pilot.core.message.Message;
import com.pilot.core.prompt.PromptLibrary;
import com.pilot.core.util.Sha256;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.tool.WorkspaceToolFactory;

import lombok.extern.slf4j.Slf4j;

/**
 * 采集工作区状态，并在状态变化时把仓库快照注入运行历史。
 *
 * <p>快照以 user 消息保存，避免动态 Git 状态破坏 system 前缀缓存；正文按工作区指纹缓存，
 * 会话内只在分支、提交或改动数量变化后追加。
 */
@Slf4j
final class RepositoryContext {
    private static final PromptLibrary PROMPTS = PromptLibrary.standard();
    private static final int MAX_TREE_ENTRIES = 30;
    private static final int MAX_GIT_CHANGES = 12;
    private static final long GIT_TIMEOUT_MS = 2_000L;
    private static final String NON_GIT = "<no-git>";
    private static final Set<String> IGNORED_ENTRIES = Set.of(".git", ".pilot", ".idea", ".vscode", ".gradle",
        ".venv", "__pycache__", "node_modules", "target", "build", "dist", "out");

    private final AgentProperties properties;
    private final WorkspaceToolFactory toolFactory;
    private final RepositoryBriefCache repositoryBriefs;
    private final Map<String, String> sessionBriefs = new ConcurrentHashMap<>();

    RepositoryContext(AgentProperties properties, WorkspaceToolFactory toolFactory,
        RepositoryBriefCache repositoryBriefs) {
        this.properties = properties;
        this.toolFactory = toolFactory;
        this.repositoryBriefs = repositoryBriefs;
    }

    List<Message> withRepositoryBrief(List<Message> history, String workspace, String sessionId) {
        if (!properties.repositoryBriefEnabled()) return history;
        Path root;
        try {
            root = toolFactory.resolve(workspace);
        } catch (RuntimeException e) {
            return history;
        }
        if (!Files.isDirectory(root)) return history;
        RepoState state = repoState(root);
        String fingerprint = sessionFingerprint(root, state);
        if (fingerprint.equals(sessionBriefs.get(sessionId))) return history;
        String brief = repositoryBrief(root, state);
        if (brief.isEmpty()) return history;
        sessionBriefs.put(sessionId, fingerprint);
        List<Message> seeded = new ArrayList<>(history);
        seeded.add(Message.user(brief));
        return List.copyOf(seeded);
    }

    /** 仓库现状包括 Git、构建方式和规范检查；full 模式还包含顶层目录结构。 */
    private String repositoryBrief(Path root, RepoState state) {
        boolean detailed = properties.repositoryBriefDetailed();
        String tree = detailed ? workspaceTree(root) : "";
        String build = buildHint(root);
        String key = RepositoryBriefCache.key(root);
        String fingerprint = Sha256.hex(state.branch() + "\u0000" + state.status() + "\u0000" + tree + "\u0000" + build);
        String cached = repositoryBriefs.cached(key, fingerprint);
        if (cached != null) return cached;
        try {
            String text = PROMPTS.render(detailed ? "context/repository" : "context/repository-brief",
                Map.of("tree", tree, "git", gitState(state, detailed), "build", build,
                    "lint", lintNote(root))).strip();
            repositoryBriefs.store(key, fingerprint, text);
            return text;
        } catch (RuntimeException e) {
            log.debug("生成仓库摘要失败", e);
            return "";
        }
    }

    private record RepoState(boolean git, String branch, String status, String head) {
    }

    private static RepoState repoState(Path root) {
        if (!Files.isDirectory(root.resolve(".git"))) return new RepoState(false, NON_GIT, null, "");
        String status = runGit(root, "status", "--porcelain=v1", "-uall");
        String head = runGit(root, "rev-parse", "HEAD");
        return new RepoState(true, branchName(root), status, head == null ? "" : head.strip());
    }

    private static String sessionFingerprint(Path root, RepoState state) {
        if (!state.git()) {
            try {
                return NON_GIT + "\u0000" + String.join("\u0000", topEntries(root));
            } catch (IOException e) {
                return NON_GIT;
            }
        }
        long dirty = state.status() == null ? -1 : state.status().lines().filter(line -> !line.isBlank()).count();
        return state.branch() + "\u0000" + dirty + "\u0000" + state.head();
    }

    private String lintNote(Path root) {
        if (!properties.lintGateEnabledValue()) return "已关闭（pilot.agent.lint-gate-enabled=false），本次不跑规范检查";
        try {
            ProjectLint lint = ProjectLint.discover(root);
            if (!lint.ready()) return "未发现仓库自己的 lint。将使用内置通用检查，只看裸数字和重复字符串";
            return lint.source() + "：`" + lint.command() + "`。运行开始后命令和规则文件会被锁定，模型不能关闭或改掉。";
        } catch (RuntimeException e) {
            return "lint 发现失败，收尾时会再试一次";
        }
    }

    private static String workspaceTree(Path root) {
        List<String> names;
        try {
            names = topEntries(root);
        } catch (IOException e) {
            return "（读取目录失败）";
        }
        if (names.isEmpty()) return "（空目录）";
        boolean truncated = names.size() > MAX_TREE_ENTRIES;
        String listed = names.stream().limit(MAX_TREE_ENTRIES).map(name -> "- " + name).collect(Collectors.joining("\n"));
        return truncated ? listed + "\n- …（其余条目省略）" : listed;
    }

    private static List<String> topEntries(Path root) throws IOException {
        try (Stream<Path> entries = Files.list(root)) {
            return entries.map(path -> {
                String name = path.getFileName().toString();
                return Files.isDirectory(path) ? name + "/" : name;
            }).filter(name -> !IGNORED_ENTRIES.contains(name.endsWith("/") ? name.substring(0, name.length() - 1) : name))
                .sorted().toList();
        }
    }

    private static String gitState(RepoState state, boolean detailed) {
        if (state.status() == null) return state.git() ? "分支 `" + state.branch() + "`（无法读取未提交改动）" : "不是 git 仓库";
        List<String> lines = state.status().lines().filter(line -> !line.isBlank()).toList();
        if (lines.isEmpty()) return "分支 `" + state.branch() + "`，工作区干净";
        if (!detailed) return "分支 `" + state.branch() + "`，" + lines.size() + " 个文件未提交";
        String listed = lines.stream().limit(MAX_GIT_CHANGES).map(line -> "- " + line.strip()).collect(Collectors.joining("\n"));
        String more = lines.size() > MAX_GIT_CHANGES ? "\n- …（还有 " + (lines.size() - MAX_GIT_CHANGES) + " 个）" : "";
        return "分支 `" + state.branch() + "`，有 " + lines.size() + " 个文件未提交：\n" + listed + more;
    }

    private static String branchName(Path root) {
        try {
            String head = Files.readString(root.resolve(".git").resolve("HEAD"), StandardCharsets.UTF_8).strip();
            String prefix = "ref: refs/heads/";
            if (head.startsWith(prefix)) return head.substring(prefix.length());
            return head.isEmpty() ? "unknown" : "detached@" + head.substring(0, Math.min(7, head.length()));
        } catch (IOException e) {
            return "unknown";
        }
    }

    private static String runGit(Path root, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("--no-pager");
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(root.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        try {
            Process process = builder.start();
            if (!process.waitFor(GIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String buildHint(Path root) {
        String childNodeProjects;
        try (Stream<Path> children = Files.list(root)) {
            childNodeProjects = children.filter(Files::isDirectory)
                .filter(path -> Files.isRegularFile(path.resolve("package.json")))
                .map(path -> path.getFileName().toString()).sorted().limit(8).collect(Collectors.joining("、"));
        } catch (IOException e) {
            childNodeProjects = "";
        }
        String nodeHint = childNodeProjects.isEmpty() ? ""
            : "；Node 子项目：" + childNodeProjects + "（先读各自 package.json，运行 npm 命令时设置对应 workdir）";
        if (Files.isRegularFile(root.resolve("pom.xml"))) return "Maven 项目：优先用工作区的 `mvnw`，不要依赖系统 maven" + nodeHint;
        if (Files.isRegularFile(root.resolve("build.gradle")) || Files.isRegularFile(root.resolve("build.gradle.kts")))
            return "Gradle 项目：用工作区的 `gradlew`";
        if (Files.isRegularFile(root.resolve("package.json")))
            return "Node 项目：先看 package.json 的 scripts，再跑 `npm test` / `npm run build` 这类脚本" + nodeHint;
        if (Files.isRegularFile(root.resolve("pyproject.toml")) || Files.isRegularFile(root.resolve("requirements.txt")))
            return "Python 项目：用 `pytest` 或项目里声明的命令";
        if (Files.isRegularFile(root.resolve("Cargo.toml"))) return "Rust 项目：`cargo test` / `cargo build`";
        if (Files.isRegularFile(root.resolve("go.mod"))) return "Go 项目：`go build ./...` / `go test ./...`";
        return "未识别到根目录构建文件，改动后按项目实际情况验证" + nodeHint;
    }
}
