package com.pilot.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.skill.SkillCatalog;
import com.pilot.core.skill.SkillSource;

class UseSkillToolTest {
    @TempDir
    Path dir;

    private SkillCatalog oneSkill() throws IOException {
        Path root = Files.createDirectories(dir.resolve("skills"));
        Path skill = Files.createDirectories(root.resolve("commit-review"));
        Files.writeString(skill.resolve("SKILL.md"), """
            ---
            name: commit-review
            description: 审阅未提交改动并按风险输出问题清单。
            command: /review
            ---
            ## 流程
            1. 先看 git status
            2. 按 references/risk-checklist.md 检查
            """);
        Files.createDirectories(skill.resolve("references"));
        Files.writeString(skill.resolve("references/risk-checklist.md"), "- 高风险：改动公共接口");
        return SkillCatalog.scan(List.of(new SkillCatalog.Root(root, SkillSource.USER)));
    }

    @Test
    void isReadOnlyToolWithExternalDescription() throws IOException {
        var tool = new UseSkillTool(oneSkill());
        assertEquals("use_skill", tool.name());
        assertTrue(tool.readOnly(), "技能是知识：读知识不需要审批，READ_ONLY 全放行");
        assertFalse(tool.parameters().isEmpty());
        assertFalse(tool.description().isBlank(), "描述来自 prompts/tools/use_skill.md");
    }

    @Test
    void missingNameArgFails() throws IOException {
        var tool = new UseSkillTool(oneSkill());
        assertTrue(tool.execute(Map.of()).startsWith("ERROR: 缺少参数"));
    }

    @Test
    void unknownSkillEchoesAvailableNames() throws IOException {
        var tool = new UseSkillTool(oneSkill());
        String result = tool.execute(Map.of("name", "no-such-skill"));
        assertTrue(result.startsWith("ERROR: 未知技能 no-such-skill"));
        assertTrue(result.contains("commit-review"), "错误信息要回显可用名单供模型修正");
    }

    @Test
    void loadsBodyWithFileFooter() throws IOException {
        var tool = new UseSkillTool(oneSkill());
        String result = tool.execute(Map.of("name", "commit-review"));
        assertTrue(result.contains("## 流程"));
        assertFalse(result.contains("description:"), "frontmatter 不能进正文");
        assertTrue(result.contains("references/risk-checklist.md"), "正文尾部要附文件清单，作为第三层的发现入口");
    }

    @Test
    void readsReferenceFileAndEchoesFilesOnMiss() throws IOException {
        var tool = new UseSkillTool(oneSkill());
        String result = tool.execute(Map.of("name", "commit-review", "file", "references/risk-checklist.md"));
        assertEquals("- 高风险：改动公共接口", result);
        String missed = tool.execute(Map.of("name", "commit-review", "file", "references/missing.md"));
        assertTrue(missed.startsWith("ERROR:"));
        assertTrue(missed.contains("references/risk-checklist.md"), "文件未命中时回显目录内可用文件");
    }

    @Test
    void traversalOutsideSkillDirectoryIsRejected() throws IOException {
        var tool = new UseSkillTool(oneSkill());
        assertTrue(tool.execute(Map.of("name", "commit-review", "file", "../outside.md")).startsWith("ERROR:"));
        assertTrue(tool.execute(Map.of("name", "commit-review", "file", "/etc/passwd")).startsWith("ERROR:"));
    }

    @Test
    void emptyCatalogYieldsUnknownSkill() {
        var tool = new UseSkillTool(SkillCatalog.EMPTY);
        assertTrue(tool.execute(Map.of("name", "anything")).contains("（无）"));
    }
}
