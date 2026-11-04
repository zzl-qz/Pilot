package com.pilot.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class SkillFrontmatterTest {

    private static final String MINIMAL = """
        ---
        description: 审阅未提交改动并按风险输出问题清单。
        ---
        # 正文
        指引内容
        """;

    @Test
    void parsesAllKnownKeys() {
        var parsed = SkillFrontmatter.parse("""
            ---
            name: commit-review
            description: 审阅未提交改动并按风险输出问题清单。
            command: /review
            argument-hint: [文件路径或关注点]
            allowed-tools: [read_file, search_text, run_command]
            ---
            正文内容
            """);
        assertEquals("commit-review", parsed.name());
        assertEquals("审阅未提交改动并按风险输出问题清单。", parsed.description());
        assertEquals("/review", parsed.command());
        assertEquals("[文件路径或关注点]", parsed.argumentHint());
        assertEquals(List.of("read_file", "search_text", "run_command"), parsed.allowedTools());
        assertEquals("正文内容", parsed.body());
    }

    @Test
    void onlyDescriptionIsRequired() {
        var parsed = SkillFrontmatter.parse(MINIMAL);
        assertNull(parsed.name());
        assertNull(parsed.command());
        assertNull(parsed.argumentHint());
        assertEquals(List.of(), parsed.allowedTools());
        assertEquals("# 正文\n指引内容", parsed.body());
    }

    @Test
    void missingFrontmatterIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SkillFrontmatter.parse("直接正文"));
        assertThrows(IllegalArgumentException.class, () -> SkillFrontmatter.parse(""));
    }

    @Test
    void unterminatedFrontmatterIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SkillFrontmatter.parse("---\ndescription: x"));
    }

    @Test
    void unknownKeyIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> SkillFrontmatter.parse("---\nversion: 1.0\ndescription: x\n---\n正文"));
    }

    @Test
    void duplicateKeyIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> SkillFrontmatter.parse("---\ndescription: a\ndescription: b\n---\n正文"));
    }

    @Test
    void missingDescriptionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SkillFrontmatter.parse("---\nname: x\n---\n正文"));
        assertThrows(IllegalArgumentException.class, () -> SkillFrontmatter.parse("---\n---\n正文"));
    }

    @Test
    void emptyOrOversizedDescriptionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SkillFrontmatter.parse("---\ndescription:\n---\n正文"));
        assertThrows(IllegalArgumentException.class,
            () -> SkillFrontmatter.parse("---\ndescription: " + "长".repeat(501) + "\n---\n正文"));
    }

    @Test
    void commandMustBeSlashPlusLowercaseWord() {
        assertThrows(IllegalArgumentException.class,
            () -> SkillFrontmatter.parse("---\ndescription: x\ncommand: review\n---\n正文"));
        assertThrows(IllegalArgumentException.class,
            () -> SkillFrontmatter.parse("---\ndescription: x\ncommand: /Review\n---\n正文"));
        assertNull(SkillFrontmatter.parse("---\ndescription: x\ncommand:\n---\n正文").command());
    }

    @Test
    void emptyNameIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> SkillFrontmatter.parse("---\nname:\ndescription: x\n---\n正文"));
    }

    @Test
    void allowedToolsAcceptsListFormsAndRejectsDuplicates() {
        assertEquals(List.of("read_file"),
            SkillFrontmatter.parse("---\ndescription: x\nallowed-tools: read_file\n---\n正文").allowedTools());
        assertEquals(List.of("read_file", "run_command"), SkillFrontmatter
            .parse("---\ndescription: x\nallowed-tools: read_file, run_command\n---\n正文").allowedTools());
        assertEquals(List.of(), SkillFrontmatter.parse("---\ndescription: x\nallowed-tools:\n---\n正文").allowedTools());
        assertThrows(IllegalArgumentException.class,
            () -> SkillFrontmatter.parse("---\ndescription: x\nallowed-tools: [read_file\n---\n正文"));
        assertThrows(IllegalArgumentException.class,
            () -> SkillFrontmatter.parse("---\ndescription: x\nallowed-tools: [a, a]\n---\n正文"));
    }

    @Test
    void bodyIsStrippedOfFrontmatterAndSurroundingWhitespace() {
        var parsed = SkillFrontmatter.parse("---\ndescription: x\n---\n\n正文内容\n\n");
        assertEquals("正文内容", parsed.body());
    }
}
