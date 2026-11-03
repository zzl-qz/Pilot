package com.pilot.server.multi;

import com.pilot.core.multi.SandboxChange;
import com.pilot.server.config.MultiAgentProperties;
import com.pilot.server.config.PilotDataDir;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 在完整的可见工作区副本叠加提案后执行容器校验；缺少隔离后端时拒绝在宿主机运行。 */
@Component
public class CommandVerificationGate implements VerificationGate {

    private final MultiAgentProperties properties;
    private final com.pilot.core.exec.ExecutionBackends.Selection execution;

    /** 兼容测试与本地装配：不启用容器隔离。 */
    public CommandVerificationGate(MultiAgentProperties properties, PilotDataDir dataDir) {
        this(properties, dataDir, com.pilot.core.exec.ExecutionBackends.select(null));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CommandVerificationGate(MultiAgentProperties properties, PilotDataDir dataDir,
                                   com.pilot.core.exec.ExecutionBackends.Selection execution) {
        this.properties = properties;
        this.execution = execution;
    }

    @Override
    public Report verify(Path workspace, String baseCommit, List<SandboxChange> changes) {
        if (changes == null || changes.isEmpty()) return Report.skipped("没有待校验的改动");
        if (!execution.isolated() || execution.degraded() || !execution.backend().available())
            return Report.skipped("隔离校验不可用：需要 Docker/Podman 容器后端，不在宿主机执行构建脚本");
        if (baseCommit != null && !baseCommit.isBlank())
            return Report.skipped("当前校验使用工作区快照，不支持指定历史 commit；请先准备对应工作区");
        Path root = workspace.toAbsolutePath().normalize();
        List<String> commands = commands(root);
        if (commands.isEmpty()) return Report.skipped("没有识别到可用的构建命令，未做硬校验");
        java.util.Map<String, String> proposed = new java.util.LinkedHashMap<>();
        for (SandboxChange change : changes) {
            if (change.status() == com.pilot.core.multi.ChangeStatus.PENDING
                    || change.status() == com.pilot.core.multi.ChangeStatus.ACCEPTED)
                proposed.put(change.path(), change.newContent());
        }
        List<String> checks = new ArrayList<>();
        try (var copy = com.pilot.core.exec.IsolatedWorkspace.create(new com.pilot.core.workspace.PathValidator(root), proposed)) {
            for (String command : commands) {
                Exec result = execBuild(copy.root(), command);
                checks.add(command + " → exit " + result.exit);
                copy.verifySourceUnchanged();
                if (result.exit != 0) return Report.failed(checks, "硬校验失败：" + command + "\n" + tail(result.output));
            }
            return Report.passed(checks);
        } catch (IOException | RuntimeException e) {
            return Report.failed(checks, "隔离校验未完成：" + e.getMessage());
        }
    }

    /** 配置优先，否则按项目文件推断；项目脚本始终按不可信代码隔离执行。 */
    private List<String> commands(Path root) {
        if (!properties.verifyCommands().isEmpty()) {
            return properties.verifyCommands();
        }
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            String wrapper = Files.isRegularFile(root.resolve("mvnw"))
                    ? "sh ./mvnw"
                    : "mvn";
            // 先编译再测试：编译不通过时测试没有意义，失败即停
            return List.of(wrapper + " -q -DskipTests compile", wrapper + " -q test");
        }
        if (Files.isRegularFile(root.resolve("build.gradle")) || Files.isRegularFile(root.resolve("build.gradle.kts"))) {
            String wrapper = Files.isRegularFile(root.resolve("gradlew"))
                    ? "sh ./gradlew"
                    : "gradle";
            return List.of(wrapper + " compileJava -q", wrapper + " test -q");
        }
        if (Files.isRegularFile(root.resolve("package.json"))) {
            return List.of("npm run build --silent");
        }
        return List.of();
    }

    private Exec execBuild(Path directory, String command) {
        com.pilot.core.exec.ExecutionLimits base = execution.settings().limits();
        com.pilot.core.exec.ExecutionLimits limits = new com.pilot.core.exec.ExecutionLimits(
                properties.verifyTimeoutSeconds(), base.maxOutputBytes(), base.memoryMb(), base.cpus(),
                base.pidsLimit());
        com.pilot.core.exec.ExecutionResult result = execution.backend().run(
                com.pilot.core.exec.ExecutionRequest.shell(command, directory, directory,
                        java.util.Map.of(), execution.settings().network(), limits));
        String output = result.output();
        if (!result.error().isEmpty()) {
            output = (output.isEmpty() ? "" : output + "\n") + result.error();
        }
        if (result.timedOut()) {
            output = (output.isEmpty() ? "" : output + "\n") + "容器执行超时（" + limits.timeoutSeconds() + " 秒）";
        }
        return new Exec(result.timedOut() || !result.error().isEmpty() ? -1 : result.exitCode(), output);
    }

    /** 命令来自配置或项目文件，不受模型控制；这里用 shell 只是为了支持带参数的命令行。 */
    private record Exec(int exit, String output) {
    }

    private static String tail(String output) {
        if (output == null) {
            return "";
        }
        String trimmed = output.strip();
        return trimmed.length() <= 4_000 ? trimmed : "…" + trimmed.substring(trimmed.length() - 4_000);
    }
}
