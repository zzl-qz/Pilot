package com.pilot.server.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.permission.PermissionDecision;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.tool.ToolCall;
import com.pilot.core.tool.UseSkillTool;
import com.pilot.server.common.data.PilotDataDir;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.SkillProperties;

import tools.jackson.databind.json.JsonMapper;

class InstallSkillToolTest {
    @TempDir
    Path temp;

    private record Fixture(SkillService skills, InstallSkillTool install, Path workspace) {
    }

    private Fixture fixture(boolean multiple) {
        Path workspace = temp.resolve("workspace");
        Path data = temp.resolve("data");
        var dataDir = new PilotDataDir(data.toString());
        var properties =
            new AgentProperties(workspace.toString(), PermissionMode.AUTO, 8, 3, 5, 300, 1, 0, 60000, false);
        var skills = new SkillService(new SkillProperties(true, null), properties, dataDir);
        var installer = new SkillInstallService(skills, dataDir, (url, target) -> {
            Path directory = Files.createDirectories(target.resolve("skills/pdf/references"));
            Files.writeString(directory.resolve("api.md"), "reference content");
            Files.writeString(directory.getParent().resolve("SKILL.md"),
                "---\nname: pdf\ndescription: PDF tools\nlicense: MIT\n---\nUse references/api.md");
            if (multiple) {
                Files.createDirectories(target.resolve("skills/other"));
                Files.writeString(target.resolve("skills/other/SKILL.md"), "---\ndescription: Other\n---\nOther body");
            }
        }, 600000, 2000, 67108864, 500, 20971520);
        return new Fixture(skills,
            new InstallSkillTool(installer, skills, JsonMapper.builder().build(), workspace.toString()), workspace);
    }

    @Test
    void installsFromLinkAndLoadsInSameRunIncludingReferences() {
        var fixture = fixture(false);
        var use = new UseSkillTool(() -> fixture.skills.catalogFor(fixture.workspace.toString()));
        assertTrue(use.execute(Map.of("name", "pdf")).startsWith("ERROR:"));
        String result = fixture.install
            .execute(Map.of("action", "install", "source", "https://github.com/team/repo/tree/main/skills/pdf"));
        assertTrue(result.contains("\"available\":true"), result);
        assertTrue(Files.exists(fixture.workspace.resolve(".pilot/skills/pdf/SKILL.md")));
        assertTrue(use.execute(Map.of("name", "pdf")).contains("Use references/api.md"));
        assertEquals("reference content", use.execute(Map.of("name", "pdf", "file", "references/api.md")));
        assertTrue(fixture.install.execute(Map.of("action", "install", "source", "https://github.com/team/repo"))
            .contains("conflict"));
    }

    @Test
    void collectionRequiresSelectionAndReusesToken() throws Exception {
        var fixture = fixture(true);
        String result = fixture.install.execute(Map.of("action", "install", "source", "https://github.com/team/repo"));
        var json = JsonMapper.builder().build().readTree(result);
        assertEquals("selection_required", json.get("status").asText());
        assertFalse(Files.exists(fixture.workspace.resolve(".pilot/skills")));
        String selected = fixture.install.execute(
            Map.of("action", "install", "token", json.get("preview").get("token").asText(), "names", List.of("pdf")));
        assertTrue(selected.contains("\"available\":true"), selected);
        assertFalse(Files.exists(fixture.workspace.resolve(".pilot/skills/other")));
    }

    @Test
    void emptySourceReportsFailureInsteadOfAskingToSelectNothing() throws Exception {
        var fixture = fixture(false);
        Path empty = Files.createDirectories(temp.resolve("empty-source"));
        String result = fixture.install.execute(Map.of("action", "install", "source", empty.toString()));
        assertTrue(result.startsWith("ERROR:"), result);
        assertTrue(result.contains("no_valid_skills"), result);
        assertFalse(Files.exists(fixture.workspace.resolve(".pilot/skills")));
    }

    @Test
    void userInstallReportsProjectShadowAndReadonlyModeDeniesInstallation() {
        var fixture = fixture(false);
        fixture.install.execute(Map.of("action", "install", "source", "https://github.com/team/repo"));
        String result = fixture.install
            .execute(Map.of("action", "install", "source", "https://github.com/team/repo", "scope", "user"));
        assertTrue(result.contains("\"available\":false"), result);
        assertTrue(result.contains("同名项目技能"), result);
        var gate = new PolicyPermissionGate(PermissionMode.READ_ONLY);
        assertEquals(PermissionDecision.DENY,
            gate.check(new ToolCall("i", "install_skill", Map.of("action", "install")), fixture.install).decision());
        assertEquals(PermissionDecision.ALLOW,
            gate.check(new ToolCall("q", "install_skill", Map.of("action", "inspect")), fixture.install).decision());
        assertTrue(fixture.install
            .execute(Map.of("action", "install", "source", "https://github.com/team/repo", "overwrite", "true"))
            .startsWith("ERROR:"));
    }
}
