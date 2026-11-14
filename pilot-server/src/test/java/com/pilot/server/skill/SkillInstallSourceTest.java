package com.pilot.server.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SkillInstallSourceTest {
    @Test
    void acceptsRepositoryDirectoryFileAndRawLinks() {
        for (String url : new String[] {"https://github.com/team/repo/tree/main/skills/pdf",
            "https://github.com/team/repo/blob/main/skills/pdf/SKILL.md",
            "https://raw.githubusercontent.com/team/repo/main/skills/pdf/SKILL.md"}) {
            assertEquals(new SkillInstallSource(true, "https://github.com/team/repo", "main", "skills/pdf"),
                SkillInstallSource.parse(url, null, null));
        }
        assertEquals(new SkillInstallSource(true, "https://gitee.com/team/repo", "v1", "pdf"),
            SkillInstallSource.parse("https://gitee.com/team/repo/raw/v1/pdf/SKILL.md", null, null));
        assertEquals(new SkillInstallSource(true, "https://github.com/team/repo.git", null, ""),
            SkillInstallSource.parse("https://github.com/team/repo.git", null, null));
    }

    @Test
    void supportsExplicitRefWithSlashAndCustomGitHost() {
        assertEquals(new SkillInstallSource(true, "https://github.com/team/repo", "release/next", "skills/pdf"),
            SkillInstallSource.parse("https://github.com/team/repo/tree/release/next/skills/pdf", "release/next",
                null));
        assertEquals(new SkillInstallSource(true, "https://git.example.org/team/repo.git", "v2", "skills/pdf"),
            SkillInstallSource.parse("https://git.example.org/team/repo.git", "v2", "skills/pdf"));
    }

    @Test
    void rejectsCredentialsTraversalOptionsAndUnsupportedFileLinks() {
        for (String url : new String[] {"https://user:password@github.com/a/b",
            "https://github.com/a/b/tree/main/%2e%2e/private", "https://github.com/a/b/blob/main/README.md",
            "https://example.org/a/b/blob/main/SKILL.md", "https://github.com/a/b/issues/1"}) {
            assertThrows(SkillInstallService.InstallException.class, () -> SkillInstallSource.parse(url, null, null),
                url);
        }
        for (String path : new String[] {"../private", "/tmp/x", "C:\\temp", "x//y", "x/./y"}) {
            assertThrows(SkillInstallService.InstallException.class,
                () -> SkillInstallSource.parse("https://github.com/a/b", null, path));
        }
        assertThrows(SkillInstallService.InstallException.class,
            () -> SkillInstallSource.parse("https://github.com/a/b", "--upload-pack=evil", null));
        assertThrows(SkillInstallService.InstallException.class,
            () -> SkillInstallSource.parse("https://github.com/a/b/tree/main/x", "other", null));
    }
}
