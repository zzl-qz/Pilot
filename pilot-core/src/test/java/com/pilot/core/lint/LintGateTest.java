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
        assertEquals("eslint . {files}", lint.command());

        Files.createDirectories(root.resolve(".pilot"));
        Files.writeString(root.resolve(".pilot/settings.json"),
            "{\"quality\":{\"command\":\"node lint.js {files}\",\"timeoutSeconds\":15}}");
        ProjectLint explicit = ProjectLint.discover(root);
        assertEquals("node lint.js {files}", explicit.command());
        assertEquals(15, explicit.timeoutSeconds());

        Files.writeString(root.resolve(".pilot/settings.json"), "{\"quality\":{\"enabled\":false}}");
        assertEquals("eslint . {files}", ProjectLint.discover(root).command());
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

        Files.createDirectories(root.resolve("scripts"));
        Files.createDirectories(root.resolve("config/quality"));
        Files.writeString(root.resolve("scripts/quality-gate.mjs"), "// gate\n");
        Files.writeString(root.resolve("config/quality/rules.json"), "{}\n");
        assertEquals("node scripts/quality-gate.mjs {files}", ProjectLint.discover(root).command());
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

        LintRunner forbidden = (workspace, command, timeout) -> {
            throw new AssertionError("开关关闭时不应启动检查进程");
        };
        LintGate.LintAssessment off = new LintGate(false, forbidden).assess(root, List.of("src/app.js"));
        assertEquals(LintGate.LintAssessment.Kind.DISABLED, off.kind());
        assertFalse(off.needsFix());

        Files.writeString(root.resolve("src/app.js"), "export const timeout = TIMEOUT_MS;\n");
        LintGate.LintAssessment clean = on.assess(root, List.of("src/app.js"));
        assertEquals(LintGate.LintAssessment.Kind.CLEAN, clean.kind());
        assertEquals(2, calls.get());
    }

    @Test
    void modelCannotTurnOffOrReplaceTheLintCommand() throws Exception {
        writeSample();
        AtomicInteger calls = new AtomicInteger();
        LintGate gate = new LintGate(true, (workspace, command, timeout) -> {
            calls.incrementAndGet();
            return new LintRunner.LintResult(1, "src/app.js:1 error 魔法值 86400\n", false, true);
        });
        assertTrue(gate.feedbackAfterEdit(root, List.of("src/app.js")).contains("lint 修正指令"));

        Files.createDirectories(root.resolve(".pilot"));
        Files.writeString(root.resolve(".pilot/settings.json"),
            "{\"quality\":{\"enabled\":false,\"command\":\"exit 0\"}}");
        Files.writeString(root.resolve("package.json"), "{\"scripts\":{\"lint\":\"exit 0\"}}");
        Files.writeString(root.resolve("lint.js"), "process.exit(0);\n");
        int before = calls.get();
        LintGate.LintAssessment blocked = gate.assess(root, List.of("src/app.js"));
        assertEquals(LintGate.LintAssessment.Kind.BLOCKED, blocked.kind());
        assertTrue(blocked.prompt().contains("被改过"), blocked.prompt());
        assertEquals(before, calls.get());
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
        assertEquals(LintGate.LintAssessment.Kind.FINDINGS, assessment.kind());
        assertTrue(assessment.prompt().contains("内置通用检查"), assessment.prompt());
        assertTrue(assessment.prompt().contains("86400"), assessment.prompt());
        assertEquals(0, calls.get());
    }

    @Test
    void immediateFeedbackSkipsWholeRepositoryCommandsAndStopsOnRepeat() throws Exception {
        Files.writeString(root.resolve("go.mod"), "module example.com/demo\n\ngo 1.22\n");
        AtomicInteger calls = new AtomicInteger();
        LintRunner runner = (workspace, command, timeout) -> {
            calls.incrementAndGet();
            return new LintRunner.LintResult(1, "src/app.js:1 error 魔法值 86400\n", false, true);
        };
        LintGate gate = new LintGate(true, runner);
        assertEquals("", gate.feedbackAfterEdit(root, List.of("src/app.js")));
        assertEquals(0, calls.get());

        Files.writeString(root.resolve("package.json"), "{\"scripts\":{\"lint\":\"node lint.js\"}}");
        LintGate scoped = new LintGate(true, runner);
        String first = scoped.feedbackAfterEdit(root, List.of("src/app.js"));
        assertTrue(first.contains("lint 修正指令"), first);
        assertTrue(first.contains("src/app.js:1"), first);
        scoped.feedbackAfterEdit(root, List.of("src/app.js"));
        LintGate.LintAssessment third = scoped.assess(root, List.of("src/app.js"));
        assertEquals(LintGate.LintAssessment.Kind.BLOCKED, third.kind());
        assertTrue(third.prompt().contains("停止"), third.prompt());
    }

    @Test
    void newSuppressionIsBlockedAndAnExistingOneIsNot() throws Exception {
        writeSample();
        Files.writeString(root.resolve("src/app.js"),
            "export const timeout = 86400; // eslint-disable-next-line no-magic-numbers\n");
        LintGate gate =
            new LintGate(true, (workspace, command, timeout) -> new LintRunner.LintResult(0, "", false, true));
        gate.setOriginalText(path -> "export const timeout = 1;\n");
        LintGate.LintAssessment added = gate.assess(root, List.of("src/app.js"));
        assertEquals(LintGate.LintAssessment.Kind.BLOCKED, added.kind());
        assertTrue(added.prompt().contains("eslint-disable"), added.prompt());

        LintGate legacy =
            new LintGate(true, (workspace, command, timeout) -> new LintRunner.LintResult(0, "", false, true));
        String same = "export const timeout = 1; // eslint-disable-next-line no-magic-numbers\n";
        Files.writeString(root.resolve("src/app.js"), same);
        legacy.setOriginalText(path -> same);
        assertEquals(LintGate.LintAssessment.Kind.CLEAN, legacy.assess(root, List.of("src/app.js")).kind());
    }

    @Test
    void editingOnlyTheLintConfigIsStopped() throws Exception {
        writeSample();
        LintGate gate =
            new LintGate(true, (workspace, command, timeout) -> new LintRunner.LintResult(0, "", false, true));
        gate.lock(root);
        Files.writeString(root.resolve("ruff.toml"), "[lint]\n");
        LintGate.LintAssessment assessment = gate.assess(root, List.of("ruff.toml"));
        assertEquals(LintGate.LintAssessment.Kind.BLOCKED, assessment.kind());
        assertTrue(assessment.prompt().contains("被改过"), assessment.prompt());
    }

    @Test
    void copyIsReportedEvenWhenTheProjectLintIsSilent() throws Exception {
        writeSample();
        String before = Files.readString(root.resolve("src/app.js"));
        String copied = """
            function buildLabel(orderId, buyer) {
                const left = orderId ? orderId.trim() : "";
                const right = buyer ? buyer.trim() : "";
                if (!left) {
                    return right;
                }
                if (!right) {
                    return left;
                }
                return left + " " + right;
            }
            """;
        String renamed = copied.replace("buildLabel", "buildTitle").replace("left", "first").replace("right", "second")
            .replace("orderId", "sku").replace("buyer", "name");
        Files.writeString(root.resolve("src/app.js"), before + "\n" + copied + "\n" + renamed);
        LintGate gate =
            new LintGate(true, (workspace, command, timeout) -> new LintRunner.LintResult(0, "", false, true));
        gate.setOriginalText(path -> "src/app.js".equals(path) ? before : null);
        LintGate.LintAssessment renamedCopy = gate.assess(root, List.of("src/app.js"));
        assertEquals(LintGate.LintAssessment.Kind.FINDINGS, renamedCopy.kind());
        assertTrue(renamedCopy.prompt().contains("换名复制"), renamedCopy.prompt());

        String once = before + "\n" + copied;
        String twice = once + "\n" + copied;
        Files.writeString(root.resolve("src/app.js"), twice);
        LintGate exact =
            new LintGate(true, (workspace, command, timeout) -> new LintRunner.LintResult(0, "", false, true));
        exact.setOriginalText(path -> "src/app.js".equals(path) ? once : null);
        LintGate.LintAssessment pasted = exact.assess(root, List.of("src/app.js"));
        assertEquals(LintGate.LintAssessment.Kind.FINDINGS, pasted.kind());
        assertTrue(pasted.prompt().contains("原样复制"), pasted.prompt());
    }

    @Test
    void projectLintSilenceIsNotOverriddenByTheBuiltinMagicCheck() throws Exception {
        writeSample();
        String before = "export const timeout = 1;\n";
        Files.writeString(root.resolve("src/app.js"), before + "export const retry = 86400;\n");
        LintGate gate =
            new LintGate(true, (workspace, command, timeout) -> new LintRunner.LintResult(0, "", false, true));
        gate.setOriginalText(path -> "src/app.js".equals(path) ? before : null);
        LintGate.LintAssessment assessment = gate.assess(root, List.of("src/app.js"));
        assertEquals(LintGate.LintAssessment.Kind.CLEAN, assessment.kind());
    }

    @Test
    void oldLintFindingsOnTheSameFileAreNotTreatedAsTheNewEdit() throws Exception {
        writeSample();
        String before = "export const timeout = 1;\n";
        Files.writeString(root.resolve("src/app.js"), before + "export const retry = 86400;\n");
        LintGate gate = new LintGate(true, (workspace, command, timeout) -> new LintRunner.LintResult(1,
            "src/app.js:1 error 旧问题 noNonNullAssertion\n", false, true));
        gate.setOriginalText(path -> "src/app.js".equals(path) ? before : null);
        LintGate.LintAssessment assessment = gate.assess(root, List.of("src/app.js"));
        assertFalse(assessment.prompt().contains("旧问题"), assessment.prompt());
        assertEquals(LintGate.LintAssessment.Kind.CLEAN, assessment.kind());
    }

    @Test
    void missingLintBinaryIsAFailureNotAPass() throws Exception {
        Files.writeString(root.resolve("package.json"), "{\"scripts\":{\"lint\":\"definitely-missing-linter\"}}");
        LintGate.LintAssessment assessment = LintGate.enabled().assess(root, List.of("src/app.js"));
        assertEquals(LintGate.LintAssessment.Kind.FAILED, assessment.kind());
        assertTrue(assessment.needsFix());
    }

    @Test
    void skipsStaticBuildBundlesSoSelfHostingFrontendBuildNeverBlowsStack() throws Exception {
        Files.createDirectories(root.resolve("pilot-web/src/main/resources/static/assets"));
        String hugeMinifiedBundle = "var a=\"" + "x".repeat(120_000) + "\";";
        Files.writeString(root.resolve("pilot-web/src/main/resources/static/assets/index-C56B2kUi.js"),
            hugeMinifiedBundle);
        assertFalse(SourceFiles.isProductionSource("pilot-web/src/main/resources/static/assets/index-C56B2kUi.js"));
        assertFalse(
            SourceFiles.isProductionSource("pilot-web/src/main/resources/static/assets/pdf.worker.min-CIqJB0yW.mjs"));
        assertFalse(SourceFiles.isProductionSource("public/chunk-Ab12Cd34.js"));
        assertTrue(SourceFiles.isProductionSource("pilot-web/frontend/src/components/App.tsx"));
        LintGate gate = LintGate.enabled();
        assertEquals("",
            gate.feedbackAfterEdit(root, List.of("pilot-web/src/main/resources/static/assets/index-C56B2kUi.js")));
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
