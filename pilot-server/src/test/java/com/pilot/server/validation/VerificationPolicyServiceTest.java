package com.pilot.server.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerificationPolicyServiceTest {
    private final VerificationPolicyService service = new VerificationPolicyService();

    @TempDir
    Path workspace;

    @Test
    void noPolicyKeepsExistingBehavior() {
        var report = service.evaluate(workspace, List.of("src/App.java"), List.of("mvn -q test → exit 0"));

        assertFalse(report.configured());
        assertTrue(report.passed());
    }

    @Test
    void matchingPathRequiresItsCommand() throws IOException {
        policy("""
            version: 1
            checks:
              "src/**":
                - "mvn -q test"
            """);

        var pass = service.evaluate(workspace, List.of("src/App.java"), List.of("mvn -q test → exit 0"));
        assertTrue(pass.configured());
        assertEquals("pilot.policy.yaml", pass.source());
        assertEquals(List.of("mvn -q test"), pass.required());
        assertTrue(pass.passed());

        var fail = service.evaluate(workspace, List.of("src/App.java"), List.of("mvn -q package → exit 0"));
        assertFalse(fail.passed());
        assertEquals(List.of("mvn -q test"), fail.missing());
    }

    @Test
    void unmatchedPathDoesNotRequireCheck() throws IOException {
        policy("""
            checks:
              "src/**": "mvn -q test"
            """);

        var report = service.evaluate(workspace, List.of("docs/readme.md"), List.of());

        assertTrue(report.configured());
        assertTrue(report.required().isEmpty());
        assertTrue(report.passed());
    }

    @Test
    void anyRequirementAcceptsOnePassingCheck() throws IOException {
        policy("""
            checks:
              "src/**":
                - "mvn -q test"
                - "mvn -q verify"
            completion:
              require: any
            """);

        var report = service.evaluate(workspace, List.of("src/App.java"), List.of("mvn -q test → exit 0"));

        assertTrue(report.passed());
        assertEquals(1, report.missing().size());
    }

    @Test
    void allowOverrideStillReportsMissingChecks() throws IOException {
        policy("""
            checks:
              "src/**": "mvn -q test"
            completion:
              allowOverride: true
            """);

        var report = service.evaluate(workspace, List.of("src/App.java"), List.of());

        assertTrue(report.passed());
        assertEquals(List.of("mvn -q test"), report.missing());
    }

    @Test
    void invalidPolicyFailsClosed() throws IOException {
        policy("""
            checks:
              "src/**": [unclosed
            """);

        var report = service.evaluate(workspace, List.of("src/App.java"), List.of("mvn -q test → exit 0"));

        assertTrue(report.configured());
        assertFalse(report.error().isBlank());
        assertFalse(report.passed());
    }

    @Test
    void hiddenPolicyIsReadWhenRootPolicyIsAbsent() throws IOException {
        Files.createDirectories(workspace.resolve(".pilot"));
        Files.writeString(workspace.resolve(".pilot/policy.yaml"), """
            checks:
              "docs/**": "node scripts/check-doc-links.mjs"
            """);

        var report =
            service.evaluate(workspace, List.of("docs/a.md"), List.of("node scripts/check-doc-links.mjs → exit 0"));

        assertTrue(report.configured());
        assertEquals(".pilot/policy.yaml", report.source());
        assertTrue(report.passed());
    }

    @Test
    void globSemanticsMatchPathsAndDirectories() {
        assertTrue(VerificationPolicyService.globMatches("src/**", "src/main/App.java"));
        assertTrue(VerificationPolicyService.globMatches("src/*.java", "src/App.java"));
        assertFalse(VerificationPolicyService.globMatches("src/*.java", "src/main/App.java"));
        assertTrue(VerificationPolicyService.globMatches("**/*.java", "src/main/App.java"));
        assertTrue(VerificationPolicyService.globMatches("**/*.java", "App.java"));
        assertTrue(VerificationPolicyService.globMatches("src", "src/main/App.java"));
        assertTrue(VerificationPolicyService.globMatches("*", "anything/at/all.txt"));
    }

    @Test
    void checkSummarySupportsAsciiAndUnicodeArrows() throws IOException {
        policy("""
            checks:
              "**": "mvn -q test"
            """);

        assertTrue(service.evaluate(workspace, List.of("src/App.java"), List.of("mvn -q test -> exit 0")).passed());
        assertTrue(service.evaluate(workspace, List.of("src/App.java"), List.of("mvn -q test → exit 0")).passed());
        assertFalse(service.evaluate(workspace, List.of("src/App.java"), List.of("mvn -q test → exit 1")).passed());
    }

    private void policy(String text) throws IOException {
        Files.writeString(workspace.resolve("pilot.policy.yaml"), text);
    }
}
