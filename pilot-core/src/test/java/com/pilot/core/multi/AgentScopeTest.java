package com.pilot.core.multi;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentScopeTest {

    @Test
    void allowGlobsLimitVisibleFiles() {
        var scope = AgentScope.readOnly(List.of("pilot-core/src/**/*.java"), List.of());

        assertTrue(scope.allowsPath("pilot-core/src/main/java/com/pilot/core/A.java"));
        assertTrue(scope.allowsPath("pilot-core/src/main/java/com/pilot/core/deep/B.java"));
        assertFalse(scope.allowsPath("pilot-server/src/main/java/com/pilot/server/C.java"));
        assertFalse(scope.allowsPath("pom.xml"));
    }

    @Test
    void denyListWinsOverAllowList() {
        var scope = AgentScope.readOnly(List.of("pilot-core/**"), List.of("pilot-core/src/test/**"));

        assertTrue(scope.allowsPath("pilot-core/src/main/java/A.java"));
        assertFalse(scope.allowsPath("pilot-core/src/test/java/A.java"));
    }

    @Test
    void doubleStarSlashMatchesZeroDirectories() {
        assertTrue(AgentScope.matches("**/*.md", "README.md"));
        assertTrue(AgentScope.matches("**/*.md", "docs/guide/a.md"));
        assertFalse(AgentScope.matches("pilot-core/**", "pilot-server/pom.xml"));
    }

    @Test
    void emptyAllowListMeansWholeWorkspace() {
        var scope = AgentScope.readWrite(List.of(), List.of("pom.xml"));

        assertTrue(scope.allowsPath("src/main/java/A.java"));
        assertFalse(scope.allowsPath("pom.xml"));
        assertTrue(scope.writable());
    }

    @Test
    void toolAllowListRestrictsTools() {
        var scope = AgentScope.readOnly(List.of(), List.of())
                .withTools(List.of("read_file", "search_text"), List.of());

        assertTrue(scope.allowsTool("read_file"));
        assertFalse(scope.allowsTool("run_command"));
        assertFalse(scope.allowsTool(""));
    }

    @Test
    void toolAllowListSupportsWildcards() {
        var scope = AgentScope.readOnly(List.of(), List.of())
                .withTools(List.of("read_*", "search_text"), List.of());

        assertTrue(scope.allowsTool("read_file"));
        assertTrue(scope.allowsTool("search_text"));
        assertFalse(scope.allowsTool("edit_file"));
        assertFalse(scope.allowsTool("list_dir"));
    }

    @Test
    void commandAllowListMatchesArgumentPrefix() {
        var scope = AgentScope.readOnly(List.of(), List.of())
                .withCommands(List.of("mvn test", "git status"), List.of());

        assertTrue(scope.allowsCommand("mvn test"));
        assertTrue(scope.allowsCommand("mvn test -q -DskipITs"));
        assertTrue(scope.allowsCommand("git status --porcelain"));
        assertFalse(scope.allowsCommand("mvn package"));
        assertFalse(scope.allowsCommand("mvn testfoo"));
        assertFalse(scope.allowsCommand(""));
    }

    @Test
    void commandDenyListWinsOverAllowList() {
        var scope = AgentScope.readOnly(List.of(), List.of())
                .withCommands(List.of("mvn"), List.of("mvn deploy"));

        assertTrue(scope.allowsCommand("mvn test"));
        assertFalse(scope.allowsCommand("mvn deploy -q"));
    }

    @Test
    void commandWildcardsMatchSingleOrRestArguments() {
        var scope = AgentScope.readOnly(List.of(), List.of())
                .withCommands(List.of("git *", "npm run **"), List.of());

        assertTrue(scope.allowsCommand("git status --porcelain"));
        assertFalse(scope.allowsCommand("git"));
        assertTrue(scope.allowsCommand("npm run build --silent"));
    }

    @Test
    void emptyCommandAllowListMeansBuiltinPolicyOnly() {
        var scope = AgentScope.readOnly(List.of(), List.of());

        assertTrue(scope.allowsCommand("mvn -q test"));
        assertFalse(scope.allowsCommand("  "));
    }

    @Test
    void scopeDescriptionTellsModelItsBoundary() {
        var scope = AgentScope.readOnly(List.of("pilot-core/**"), List.of("pilot-core/secret/**"));

        String text = scope.describe();

        assertTrue(text.contains("pilot-core/**"));
        assertTrue(text.contains("禁止访问"));
        assertTrue(text.contains("只读"));
    }
}
