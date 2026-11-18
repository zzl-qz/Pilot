package com.pilot.core.lint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 用三个小样例对照开关：有 lint 且打开、同一仓库关掉、没有 lint 配置。
 * 样例的检查器是一段 node 脚本，不依赖本机装了 eslint 或 ruff。
 */
class LintGateTest {
    @TempDir
    Path root;

    @Test
    void discoversCommandsFromRootManifestsOnly() throws Exception {
        Files.writeString(root.resolve("package.json"), "{\"scripts\":{\"lint\":\"eslint .\"}}");
        Files.writeString(root.resolve("pnpm-lock.yaml"), "");
        ProjectLint lint = ProjectLint.discover(root);
        assertEquals(ProjectLint.Status.READY, lint.status());
        assertEquals("pnpm run lint -- {files}", lint.command());

        Files.createDirectories(root.resolve(".pilot"));
        Files.writeString(root.resolve(".pilot/settings.json"),
            "{\"quality\":{\"command\":\"node lint.js {files}\",\"timeoutSeconds\":15}}");
        ProjectLint explicit = ProjectLint.discover(root);
        assertEquals("node lint.js {files}", explicit.command());
        assertEquals(15, explicit.timeoutSeconds());

        Files.writeString(root.resolve(".pilot/settings.json"), "{\"quality\":{\"enabled\":false}}");
        assertEquals(ProjectLint.Status.DISABLED, ProjectLint.discover(root).status());
    }

    @Test
    void discoversLanguageMarkersWithoutWalkingTheTree() throws Exception {
        Files.createDirectories(root.resolve("vendor/ruff.toml"));
        Files.writeString(root.resolve("ruff.toml"), "");
        assertEquals("ruff check {files}", ProjectLint.discover(root).command());

        Files.delete(root.resolve("ruff.toml"));
        Files.writeString(root.resolve("go.mod"), "module example.com/demo\n\ngo 1.22\n");
        assertEquals("go vet ./...", ProjectLint.discover(root).command());

        Files.delete(root.resolve("go.mod"));
        assertEquals(ProjectLint.Status.UNCONFIGURED, ProjectLint.discover(root).status());
    }

    @Test
    void switchOnReportsOnlyChangedFilesAndSwitchOffRunsNothing() throws Exception {
        writeSample();
        AtomicInteger calls = new AtomicInteger();
        LintRunner counting = (workspace, command, timeout) -> {
            calls.incrementAndGet();
            return ProcessLintRunner.INSTANCE.run(workspace, command, timeout);
        };
        LintGate on = new LintGate(true, counting);
        LintGate.LintAssessment failed = on.assess(root, List.of("src/app.js"));
        assertEquals(LintGate.LintAssessment.Kind.FINDINGS, failed.kind());
        assertTrue(failed.prompt().contains("src/app.js"), failed.prompt());
        assertFalse(failed.prompt().contains("src/legacy.js"), failed.prompt());
        assertTrue(failed.prompt().contains("86400"), failed.prompt());
        assertEquals(1, calls.get());

        Files.createDirectories(root.resolve(".pilot"));
        Files.writeString(root.resolve(".pilot/settings.json"),
            "{\"quality\":{\"command\":\"node lint.js src/app.js src/legacy.js\"}}");
        LintGate.LintAssessment filtered = on.assess(root, List.of("src/app.js"));
        assertEquals(LintGate.LintAssessment.Kind.FINDINGS, filtered.kind());
        assertTrue(filtered.prompt().contains("src/app.js:1"), filtered.prompt());
        assertFalse(filtered.prompt().contains("src/legacy.js:"), filtered.prompt());
        assertEquals(2, calls.get());

        LintRunner forbidden = (workspace, command, timeout) -> {
            throw new AssertionError("开关关闭时不应启动检查进程");
        };
        LintGate.LintAssessment off = new LintGate(false, forbidden).assess(root, List.of("src/app.js"));
        assertEquals(LintGate.LintAssessment.Kind.DISABLED, off.kind());
        assertFalse(off.needsFix());

        Files.writeString(root.resolve("src/app.js"), "export const timeout = TIMEOUT_MS;\n");
        LintGate.LintAssessment clean = on.assess(root, List.of("src/app.js"));
        assertEquals(LintGate.LintAssessment.Kind.CLEAN, clean.kind());
        assertEquals(3, calls.get());
    }

    @Test
    void unconfiguredWorkspaceDoesNotPretendLintPassedByRunningACommand() throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/app.js"), "export const n = 86400;\n");
        AtomicInteger calls = new AtomicInteger();
        LintGate gate = new LintGate(true, (workspace, command, timeout) -> {
            calls.incrementAndGet();
            return LintRunner.LintResult.failedToStart("不应该执行");
        });
        LintGate.LintAssessment assessment = gate.assess(root, List.of("src/app.js"));
        assertEquals(LintGate.LintAssessment.Kind.UNCONFIGURED, assessment.kind());
        assertEquals(0, calls.get());
    }

    @Test
    void missingLintBinaryIsAFailureNotAPass() throws Exception {
        Files.writeString(root.resolve("package.json"), "{\"scripts\":{\"lint\":\"definitely-missing-linter\"}}");
        LintGate.LintAssessment assessment = LintGate.enabled().assess(root, List.of("src/app.js"));
        assertEquals(LintGate.LintAssessment.Kind.FAILED, assessment.kind());
        assertTrue(assessment.needsFix());
    }

    private void writeSample() throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("package.json"), "{\"scripts\":{\"lint\":\"node lint.js\"}}");
        Files.writeString(root.resolve("lint.js"), """
            const fs = require('fs');
            let failed = false;
            for (const file of process.argv.slice(2)) {
              const lines = fs.readFileSync(file, 'utf8').split(/\\n/);
              lines.forEach((line, index) => {
                if (/\\b86400\\b/.test(line)) {
                  console.log(file + ':' + (index + 1) + ' error 魔法值 86400');
                  failed = true;
                }
              });
            }
            process.exit(failed ? 1 : 0);
            """);
        Files.writeString(root.resolve("src/app.js"), "export const timeout = 86400;\n");
        Files.writeString(root.resolve("src/legacy.js"), "export const old = 86400;\n");
    }
}
