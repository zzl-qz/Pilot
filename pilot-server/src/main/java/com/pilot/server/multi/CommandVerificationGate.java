package com.pilot.server.multi;

import com.pilot.core.exec.*;
import com.pilot.core.multi.SandboxChange;
import com.pilot.core.multi.ChangeStatus;
import com.pilot.core.workspace.PathValidator;
import com.pilot.server.config.MultiAgentProperties;
import com.pilot.server.config.PilotDataDir;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** 物化候选版本后检查工具链、准备依赖并构建测试；宿主工作区和用户缓存不进入容器。 */
@Component
public class CommandVerificationGate implements VerificationGate {
    private final MultiAgentProperties properties;
    private final ExecutionBackends.Selection execution;
    private final Path cacheRoot;

    public CommandVerificationGate(MultiAgentProperties properties, PilotDataDir dataDir) {
        this(properties, dataDir, ExecutionBackends.select(null));
    }
    @org.springframework.beans.factory.annotation.Autowired
    public CommandVerificationGate(MultiAgentProperties properties, PilotDataDir dataDir, ExecutionBackends.Selection execution) {
        this.properties = properties; this.execution = execution;
        this.cacheRoot = execution.settings().cacheRoot() == null ? dataDir.file("build-cache") : execution.settings().cacheRoot();
    }

    @Override public Report verify(Path workspace, String baseCommit, List<SandboxChange> changes) {
        if (changes == null || changes.isEmpty()) return Report.skipped("没有待校验的改动");
        if (!execution.isolated() || execution.degraded() || !execution.backend().available())
            return Report.skipped("隔离校验不可用：需要 Docker/Podman 容器后端，不在宿主机执行构建脚本。" + execution.reason());
        if (baseCommit != null && !baseCommit.isBlank())
            return Report.skipped("当前校验使用工作区快照，不支持指定历史 commit；请先准备对应工作区");
        Map<String, String> proposed = new LinkedHashMap<>();
        for (SandboxChange change : changes) if (change.status() == ChangeStatus.PENDING || change.status() == ChangeStatus.ACCEPTED)
            proposed.put(change.path(), change.newContent());
        List<String> checks = new ArrayList<>();
        try (var copy = IsolatedWorkspace.createForBuild(new PathValidator(workspace), proposed)) {
            // 项目类型、锁文件与命令都从实际候选读取，提案新增 package.json 等也能正确校验。
            Plan plan = plan(copy.root());
            if (plan.commands().isEmpty()) return Report.skipped("没有识别到构建或测试脚本，请配置 pilot.multi.verify-commands");
            checks.add("网络：" + (execution.settings().network() ? "已配置允许依赖下载" : "关闭，仅使用本项目已有依赖缓存"));
            if (!copy.excludedDirectories().isEmpty()) checks.add("副本排除生成目录：" + String.join("、", copy.excludedDirectories()));
            try (var cache = DependencyCache.acquire(cacheRoot, workspace, execution.backend().cacheIdentity(), copy.root())) {
                List<String> steps = new ArrayList<>();
                if (!plan.tools().isEmpty()) steps.add(String.join(" && ", plan.tools().stream()
                        .map(tool -> "command -v " + tool + " >/dev/null || { echo '缺少工具链: " + tool + "'; exit 127; }").toList()));
                steps.addAll(plan.commands());
                for (String command : steps) {
                    long started = System.nanoTime();
                    var result = execBuild(copy.root(), cache.path(), command);
                    checks.add(command + " → exit " + result.exitCode() + " / " + (System.nanoTime() - started) / 1_000_000 + "ms");
                    copy.verifySourceUnchanged();
                    if (!result.ok()) return Report.failed(checks, "隔离校验失败：" + command + "\n" + tail(result.output() + "\n" + result.error())
                            + (result.timedOut() ? "\n命令超时" : "")
                            + (!execution.settings().network() ? "\n当前网络关闭；若依赖缺失，请在明确启用构建网络后预热本项目缓存，再重试离线校验。" : ""));
                }
            }
            return Report.passed(checks);
        } catch (IOException | RuntimeException e) {
            return Report.failed(checks, "隔离校验未完成：" + e.getMessage());
        }
    }

    private record Plan(List<String> tools, List<String> commands) { }
    private Plan plan(Path root) throws IOException {
        if (!properties.verifyCommands().isEmpty()) return new Plan(List.of(), properties.verifyCommands());
        boolean online = execution.settings().network();
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            boolean wrapper = Files.isRegularFile(root.resolve("mvnw"));
            String mvn = wrapper ? "sh ./mvnw" : "mvn";
            // verify 包含编译、单测及项目配置的集成测试；失败立即停止。
            return new Plan(wrapper ? List.of("java", "sh") : List.of("java", "mvn"),
                    List.of(mvn + " -B -ntp" + (online ? "" : " -o") + " verify"));
        }
        if (Files.isRegularFile(root.resolve("build.gradle")) || Files.isRegularFile(root.resolve("build.gradle.kts"))) {
            boolean wrapper = Files.isRegularFile(root.resolve("gradlew"));
            return new Plan(wrapper ? List.of("java", "sh") : List.of("java", "gradle"),
                    List.of((wrapper ? "sh ./gradlew" : "gradle") + " --no-daemon" + (online ? "" : " --offline") + " build"));
        }
        if (Files.isRegularFile(root.resolve("package.json"))) {
            var json = JsonMapper.builder().build().readTree(Files.readString(root.resolve("package.json")));
            String manager = json.path("packageManager").asString("");
            if ((!manager.isEmpty() && !manager.startsWith("npm@")) || Files.exists(root.resolve("pnpm-lock.yaml")) || Files.exists(root.resolve("yarn.lock")))
                throw new IOException("此项目使用其他包管理器，请配置明确的隔离校验命令；不会用 npm 改写锁文件");
            List<String> commands = new ArrayList<>();
            boolean dependencies = !json.path("dependencies").isEmpty() || !json.path("devDependencies").isEmpty();
            boolean lock = Files.isRegularFile(root.resolve("package-lock.json")) || Files.isRegularFile(root.resolve("npm-shrinkwrap.json"));
            if (dependencies && !lock) throw new IOException("npm 项目缺少锁文件，请先生成并审阅 package-lock.json，再执行 npm ci 校验");
            boolean build = json.path("scripts").path("build").isString(), test = json.path("scripts").path("test").isString();
            if (!build && !test) return new Plan(List.of(), List.of());
            if (lock) commands.add("npm ci --no-audit --no-fund" + (online ? "" : " --offline"));
            if (build) commands.add("npm run build --silent");
            if (test) commands.add("npm test --silent");
            return new Plan(List.of("node", "npm"), List.copyOf(commands));
        }
        return new Plan(List.of(), List.of());
    }

    private ExecutionResult execBuild(Path directory, Path cache, String command) {
        var base = execution.settings().limits();
        var limits = new ExecutionLimits(properties.verifyTimeoutSeconds(), base.maxOutputBytes(), base.memoryMb(), base.cpus(), base.pidsLimit());
        return execution.backend().run(ExecutionRequest.shell(command, directory, directory,
                Map.of("CI", "true"), execution.settings().network(), limits).withDependencyCache(cache));
    }
    private static String tail(String output) {
        String trimmed = output.strip();
        return trimmed.length() <= 4_000 ? trimmed : "…" + trimmed.substring(trimmed.length() - 4_000);
    }
}
