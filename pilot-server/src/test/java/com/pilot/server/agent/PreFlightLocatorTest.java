package com.pilot.server.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.workspace.AgentScope;
import com.pilot.core.workspace.PathValidator;

class PreFlightLocatorTest {
    @TempDir
    Path workspace;

    @Test
    void locatesHighConfidencePathIdentifierAndLiteralInBothRipgrepAndFallback() throws Exception {
        Files.createDirectories(workspace.resolve("src/billing"));
        Files.createDirectories(workspace.resolve("src/auth"));
        Files.writeString(workspace.resolve("src/billing/InvoiceRetryScheduler.java"), """
            package billing;
            public class InvoiceRetryScheduler {
                public static final int MAX_RETRY_BACKOFF = 30;
                void schedule() { String msg = "账单重试已超上限"; }
            }
            """);
        Files.writeString(workspace.resolve("src/auth/LoginController.java"), """
            package auth;
            public class LoginController {
                String endpoint = "/api/v2/sessions/revoke";
            }
            """);

        PathValidator validator = new PathValidator(workspace);
        String prompt =
            "排查 InvoiceRetryScheduler 里 `MAX_RETRY_BACKOFF` 触发 \"账单重试已超上限\" 的问题，顺便核对 src\\auth\\LoginController.java";

        for (PreFlightLocator locator : List.of(new PreFlightLocator(), new PreFlightLocator(false, 300, 500, 6))) {
            PreFlightLocator.Result result = locator.locate(workspace, validator, prompt, Set.of());
            assertTrue(result.hasCandidates(), "应命中高置信候选");
            assertTrue(result.candidatePaths().contains("src/billing/InvoiceRetryScheduler.java"),
                result.candidates().toString());
            assertTrue(result.candidatePaths().contains("src/auth/LoginController.java"),
                result.candidates().toString());
            String block = result.renderBlock();
            assertTrue(block.startsWith("<candidate-targets status=\"unverified-hypothesis\">"), block);
            assertTrue(block.length() <= PreFlightLocator.MAX_RENDER_CHARS, "渲染长度不得超上限");
        }
    }

    @Test
    void skipsTrivialPromptsAndDiscardsOverlyGenericAnchors() throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        for (int i = 1; i <= 25; i++) {
            Files.writeString(workspace.resolve("src/File" + i + ".java"),
                "class File" + i + " { String tag = \"CommonMarkerToken\"; }\n");
        }
        PathValidator validator = new PathValidator(workspace);
        PreFlightLocator locator = new PreFlightLocator(false, 300, 500, 6);

        assertFalse(locator.locate(workspace, validator, "你好", Set.of()).hasCandidates());
        assertFalse(locator.locate(workspace, validator, "继续", Set.of()).hasCandidates());
        assertFalse(locator.locate(workspace, validator, "帮我整体看看这个项目有什么可以改进的", Set.of()).hasCandidates());

        // 命中 25 个文件 (> 20) 的泛词必须被丢弃，不能注入低质量猜测
        PreFlightLocator.Result generic = locator.locate(workspace, validator, "检查一下 CommonMarkerToken 的用法", Set.of());
        assertFalse(generic.hasCandidates(), "命中超过 20 个文件的泛词应丢弃: " + generic.candidates());
    }

    @Test
    void excludesSensitiveFilesOutOfScopePathsAndAlreadyExcludedFiles() throws Exception {
        Files.createDirectories(workspace.resolve("src/public"));
        Files.createDirectories(workspace.resolve("src/private"));
        Files.writeString(workspace.resolve(".env"), "SPECIAL_SECRET_KEY=123\n");
        Files.writeString(workspace.resolve("src/private/HiddenSecretToken.java"), "class HiddenSecretToken {}\n");
        Files.writeString(workspace.resolve("src/public/VisibleTargetToken.java"), "class VisibleTargetToken {}\n");

        AgentScope scoped = AgentScope.readOnly(List.of("src/public/**"), List.of());
        PathValidator validator = new PathValidator(workspace, scoped);
        PreFlightLocator locator = new PreFlightLocator(false, 300, 500, 6);

        PreFlightLocator.Result result = locator.locate(workspace, validator,
            "查 SPECIAL_SECRET_KEY、HiddenSecretToken、VisibleTargetToken 与 .env", Set.of());
        assertTrue(result.candidatePaths().contains("src/public/VisibleTargetToken.java"));
        assertFalse(result.candidatePaths().contains(".env"));
        assertFalse(result.candidatePaths().contains("src/private/HiddenSecretToken.java"));

        // excludePaths 生效（用于 P2 计划推进排除已读/已改文件）
        PreFlightLocator.Result excluded =
            locator.locate(workspace, validator, "查 VisibleTargetToken", Set.of("src/public/VisibleTargetToken.java"));
        assertFalse(excluded.hasCandidates());
    }

    @Test
    void locatesRealPilotRepositoryTargetsQuicklyAndAccurately() {
        Path repoRoot = Path.of("").toAbsolutePath().normalize();
        if (!Files.isDirectory(repoRoot.resolve("pilot-core")) && repoRoot.getParent() != null
            && Files.isDirectory(repoRoot.getParent().resolve("pilot-core"))) {
            repoRoot = repoRoot.getParent();
        }
        if (!Files.isDirectory(repoRoot.resolve("pilot-core"))) {
            return;
        }
        PreFlightLocator locator = new PreFlightLocator();
        PreFlightLocator.Result result = locator.locate(repoRoot, new PathValidator(repoRoot),
            "优化 ReadTracker 的区间去重和 GoalExecutionPolicy 的 investigationGuidance 逻辑", Set.of());

        assertTrue(result.hasCandidates(), "在真实 Pilot 仓库应能命中目标文件");
        assertTrue(result.candidatePaths().stream().anyMatch(p -> p.endsWith("ReadTracker.java")),
            result.candidates().toString());
        assertTrue(result.candidatePaths().stream().anyMatch(p -> p.endsWith("GoalExecutionPolicy.java")),
            result.candidates().toString());
        assertTrue(result.elapsedMs() <= 500L, "真实仓库预定位耗时应在 500ms 内，实际: " + result.elapsedMs() + "ms");
    }

    @Test
    void exactStemDefinitionOutranksMultipleTestReferences() throws Exception {
        Files.createDirectories(workspace.resolve("src/main/java/demo"));
        Files.createDirectories(workspace.resolve("src/test/java/demo"));
        Files.writeString(workspace.resolve("src/main/java/demo/ReadTracker.java"), "public class ReadTracker {}\n");
        Files.writeString(workspace.resolve("src/main/java/demo/ReadFileTool.java"),
            "public class ReadFileTool { static final int DEFAULT_LINES = 200; ReadTracker tracker; }\n");
        for (int i = 1; i <= 7; i++) {
            Files.writeString(workspace.resolve("src/test/java/demo/Caller" + i + "Test.java"),
                "class Caller" + i + "Test { ReadFileTool tool; ReadTracker tracker; }\n");
        }
        PreFlightLocator locator = new PreFlightLocator(false, 300, 500, 6);
        PreFlightLocator.Result result = locator.locate(workspace, new PathValidator(workspace),
            "修复 ReadTracker 的区间覆盖和 ReadFileTool 的 DEFAULT_LINES", Set.of());

        assertTrue(result.candidatePaths().contains("src/main/java/demo/ReadTracker.java"),
            "类型定义文件 ReadTracker.java 不应被多个引用两者的测试文件挤出前 6 名: " + result.candidates());
        assertTrue(result.candidatePaths().contains("src/main/java/demo/ReadFileTool.java"),
            result.candidates().toString());
    }

    @Test
    void prioritizesPathsAndIdentifiersOverLiteralsAndFiltersCliNoise() throws Exception {
        Files.createDirectories(workspace.resolve("src/main/java/demo"));
        Files.createDirectories(workspace.resolve("src/test/java/demo"));
        Files.writeString(workspace.resolve("src/main/java/demo/MeteredModelClient.java"),
            "public class MeteredModelClient {}\n");
        Files.writeString(workspace.resolve("src/main/java/demo/UsageService.java"), "public class UsageService {}\n");
        Files.writeString(workspace.resolve("src/test/java/demo/RunCommandNoiseTest.java"),
            "class RunCommandNoiseTest { String a = \"git commit\"; String b = \"spotless:check\"; String c = \"checkstyle:check\"; String d = \"quality-gate\"; }\n");

        PreFlightLocator locator = new PreFlightLocator(false, 300, 500, 6);
        String prompt = "重构 MeteredModelClient 和 UsageService，消除 `high-cyclomatic-complexity` 与 `long-method`，"
            + "要求跑过 `./mvnw test`、`spotless:check`、`checkstyle:check` 与 `quality-gate`，严禁执行 `git commit` 和 `git push`，"
            + "同时注意 \"第一条普通中文提示\"、\"第二条普通中文提示\"、\"第三条普通中文提示\"、\"第四条普通中文提示\"、"
            + "\"第五条普通中文提示\"、\"第六条普通中文提示\"、\"第七条普通中文提示\"、\"第八条普通中文提示\"。";

        PreFlightLocator.Result result = locator.locate(workspace, new PathValidator(workspace), prompt, Set.of());
        assertTrue(result.candidatePaths().contains("src/main/java/demo/MeteredModelClient.java"),
            "高优先级标识符不应被普通字面量挤出 MAX_ANCHORS: " + result.candidates());
        assertTrue(result.candidatePaths().contains("src/main/java/demo/UsageService.java"),
            result.candidates().toString());
        assertFalse(result.candidatePaths().contains("src/test/java/demo/RunCommandNoiseTest.java"),
            "CLI/构建/质量门禁命令字面量不应污染候选列表: " + result.candidates());
    }
}
