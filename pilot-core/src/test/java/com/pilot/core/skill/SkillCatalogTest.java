package com.pilot.core.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillCatalogTest {
    @TempDir Path dir;

    private Path write(Path root, String name, String frontmatter, String body) throws IOException {
        Path skill = Files.createDirectories(root.resolve(name));
        Files.writeString(skill.resolve("SKILL.md"), frontmatter + "---\n" + body);
        return skill;
    }

    private static List<SkillCatalog.Root> roots(Path project, Path user) {
        return List.of(new SkillCatalog.Root(project, SkillSource.PROJECT),
                new SkillCatalog.Root(user, SkillSource.USER));
    }

    /** 项目级根不存在时的扫描入参。 */
    private Path noProject() {
        return dir.resolve("no-project-root");
    }

    @Test
    void projectShadowsUserOnSameName() throws IOException {
        Path project = Files.createDirectories(dir.resolve("project"));
        Path user = Files.createDirectories(dir.resolve("user"));
        write(project, "shared", "---\ndescription: 项目级版本。\n", "项目正文");
        write(user, "shared", "---\ndescription: 用户级版本。\n", "用户正文");
        write(user, "personal", "---\ndescription: 个人技能。\n", "个人正文");

        var catalog = SkillCatalog.scan(roots(project, user));

        assertEquals(2, catalog.list().size());
        assertTrue(catalog.find("shared").orElseThrow().body().contains("项目正文"),
                "项目级应遮蔽用户级同名技能");
        assertEquals(SkillSource.PROJECT, catalog.find("shared").orElseThrow().manifest().source());
        assertEquals(SkillSource.USER, catalog.find("personal").orElseThrow().manifest().source());
    }

    @Test
    void invalidSkillIsClosedToModelAndVisibleToUser() throws IOException {
        Path root = Files.createDirectories(dir.resolve("skills"));
        write(root, "no-description", "---\nname: no-description\n", "正文");
        write(root, "name-mismatch", "---\nname: 别的名字\ndescription: x\n", "正文");
        write(root, "empty-body", "---\ndescription: x\n", "\n\n");
        write(root, "bad-directory-name!", "---\ndescription: x\n", "正文");
        Files.createDirectories(root.resolve("not-a-skill").resolve("sub"));
        write(root, "good", "---\ndescription: 正常技能。\n", "正文");

        var catalog = SkillCatalog.scan(roots(noProject(), root));

        assertEquals(1, catalog.list().size());
        assertEquals("good", catalog.list().get(0).name());
        assertEquals(4, catalog.invalid().size());
        assertTrue(catalog.find("no-description").isEmpty(), "fail-closed：无效技能不可用");
        assertTrue(catalog.find("bad-directory-name!").isEmpty());
        assertTrue(catalog.invalid().stream().anyMatch(problem -> problem.reason().contains("description")));
        assertTrue(catalog.invalid().stream().anyMatch(problem -> problem.reason().contains("不一致")));
        assertTrue(catalog.invalid().stream().anyMatch(problem -> problem.reason().contains("目录名")));
    }

    @Test
    void bodyLimitsAreEnforced() throws IOException {
        Path root = Files.createDirectories(dir.resolve("skills"));
        write(root, "too-many-lines", "---\ndescription: x\n",
                String.join("\n", IntStream.range(0, 501).mapToObj(i -> "行" + i).toList()));
        write(root, "within-limits", "---\ndescription: x\n",
                String.join("\n", IntStream.range(0, 500).mapToObj(i -> "行" + i).toList()));

        var catalog = SkillCatalog.scan(roots(noProject(), root));

        assertEquals(1, catalog.list().size());
        assertTrue(catalog.find("within-limits").isPresent());
        assertTrue(catalog.invalid().get(0).name().equals("too-many-lines"));
    }

    @Test
    void skillCountOverflowSkipsAndReports() throws IOException {
        Path root = Files.createDirectories(dir.resolve("skills"));
        for (int i = 0; i < SkillCatalog.MAX_SKILLS + 1; i++) {
            write(root, "skill-" + i, "---\ndescription: 技能 " + i + "\n", "正文");
        }
        var catalog = SkillCatalog.scan(roots(noProject(), root));

        assertEquals(SkillCatalog.MAX_SKILLS, catalog.list().size());
        assertEquals(1, catalog.invalid().size());
        assertTrue(catalog.invalid().get(0).reason().contains("上限"));
    }

    @Test
    void duplicateCommandsInvalidateTheLaterDirectory() throws IOException {
        Path root = Files.createDirectories(dir.resolve("skills"));
        write(root, "alpha", "---\ndescription: 甲\ncommand: /review\n", "正文");
        write(root, "beta", "---\ndescription: 乙\ncommand: /review\n", "正文");

        var catalog = SkillCatalog.scan(roots(noProject(), root));

        assertEquals(1, catalog.list().size());
        assertEquals("alpha", catalog.list().get(0).name());
        assertTrue(catalog.invalid().stream().anyMatch(problem -> problem.name().equals("beta")));
    }

    @Test
    void filesListExcludesSkillMdAndUsesSlashSeparators() throws IOException {
        Path root = Files.createDirectories(dir.resolve("skills"));
        write(root, "rich", "---\ndescription: x\n", "正文");
        Files.createDirectories(root.resolve("rich/references/nested"));
        Files.createDirectories(root.resolve("rich/scripts"));
        Files.writeString(root.resolve("rich/references/api.md"), "参考");
        Files.writeString(root.resolve("rich/references/nested/deep.md"), "深层");
        Files.writeString(root.resolve("rich/scripts/run.py"), "print(1)");

        var skill = SkillCatalog.scan(roots(noProject(), root)).find("rich").orElseThrow();

        assertEquals(List.of("references/api.md", "references/nested/deep.md", "scripts/run.py"), skill.files());
    }

    @Test
    void filesListSkipsGitMetadataFromManuallyClonedSkills() throws IOException {
        // 手动 clone 进技能目录是合法安装方式，但 .git 里的元数据不该出现在 use_skill 的清单里
        Path root = Files.createDirectories(dir.resolve("skills"));
        write(root, "cloned", "---\ndescription: x\n", "正文");
        Files.createDirectories(root.resolve("cloned/.git/refs/heads"));
        Files.createDirectories(root.resolve("cloned/references"));
        Files.writeString(root.resolve("cloned/.git/HEAD"), "ref: refs/heads/main");
        Files.writeString(root.resolve("cloned/.git/config"), "[core]");
        Files.writeString(root.resolve("cloned/references/api.md"), "参考");

        var skill = SkillCatalog.scan(roots(noProject(), root)).find("cloned").orElseThrow();

        assertEquals(List.of("references/api.md"), skill.files());
        assertFalse(skill.files().stream().anyMatch(file -> file.contains(".git")));
    }

    @Test
    void readFileEnforcesContainmentAndLineLimits() throws IOException {
        Path root = Files.createDirectories(dir.resolve("skills"));
        write(root, "demo", "---\ndescription: x\n", "正文");
        Files.writeString(root.resolve("demo/notes.md"), "第一行\n第二行");
        var skill = SkillCatalog.scan(roots(noProject(), root)).find("demo").orElseThrow();

        assertEquals("第一行\n第二行", SkillCatalog.scan(roots(noProject(), root)).readFile(skill, "notes.md"));

        var catalog = SkillCatalog.scan(roots(noProject(), root));
        assertThrows(IllegalArgumentException.class, () -> catalog.readFile(skill, "../demo/notes.md"));
        assertThrows(IllegalArgumentException.class, () -> catalog.readFile(skill, "missing.md"));
        assertThrows(IllegalArgumentException.class, () -> catalog.readFile(skill, "/etc/passwd"));

        Path longFile = root.resolve("demo/long.md");
        Files.write(longFile, IntStream.range(0, SkillCatalog.MAX_FILE_LINES + 10)
                .mapToObj(i -> "行" + i).toList());
        String truncated = catalog.readFile(skill, "long.md");
        assertTrue(truncated.contains("已截断"));
        assertTrue(truncated.lines().count() <= SkillCatalog.MAX_FILE_LINES + 1);

        Files.write(root.resolve("demo/blob.bin"), new byte[]{1, 2, 0, 4});
        assertThrows(IllegalArgumentException.class, () -> catalog.readFile(skill, "blob.bin"));
    }

    @Test
    void symlinkEscapeIsRejectedWhenSupported() throws IOException {
        Path root = Files.createDirectories(dir.resolve("skills"));
        write(root, "demo", "---\ndescription: x\n", "正文");
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Files.writeString(outside.resolve("secret.md"), "越界内容");
        Path link = root.resolve("demo/escape.md");
        try {
            Files.createSymbolicLink(link, outside.resolve("secret.md"));
        } catch (IOException | UnsupportedOperationException e) {
            // Windows 创建符号链接需要特权；跳过而非失败
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "本机不支持创建符号链接");
            return;
        }
        var catalog = SkillCatalog.scan(roots(noProject(), root));
        var skill = catalog.find("demo").orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> catalog.readFile(skill, "escape.md"));
    }

    @Test
    void emptyAndMissingRootsYieldEmptyCatalog() {
        assertTrue(SkillCatalog.scan(List.of()).list().isEmpty());
        assertTrue(SkillCatalog.scan(roots(dir.resolve("missing-project"), dir.resolve("missing-user")))
                .list().isEmpty());
        assertTrue(SkillCatalog.EMPTY.list().isEmpty());
        assertTrue(SkillCatalog.EMPTY.find("any").isEmpty());
    }

    @Test
    void concurrentScansAreSafe() throws Exception {
        Path root = Files.createDirectories(dir.resolve("skills"));
        write(root, "one", "---\ndescription: 唯一\n", "正文");

        var failure = new AtomicReference<RuntimeException>();
        var ready = new CountDownLatch(32);
        var done = new CountDownLatch(32);
        IntStream.range(0, 32).forEach(ignored -> Thread.ofVirtual().start(() -> {
            ready.countDown();
            try {
                assertEquals(1, SkillCatalog.scan(roots(noProject(), root)).list().size());
            } catch (RuntimeException e) {
                failure.compareAndSet(null, e);
            } finally {
                done.countDown();
            }
        }));
        ready.await();
        done.await();
        if (failure.get() != null) {
            throw failure.get();
        }
    }
}
