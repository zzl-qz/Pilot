package com.pilot.server.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.permission.PermissionMode;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.PilotDataDir;
import com.pilot.server.config.SkillProperties;

/** 安装器单测：全部走本机目录来源或假 Cloner，永不依赖网络与真实 git。 */
class SkillInstallServiceTest {
    @TempDir
    Path workspace;
    @TempDir
    Path dataRoot;
    @TempDir
    Path source;

    private PilotDataDir dataDir;

    @BeforeEach
    void setUp() {
        dataDir = new PilotDataDir(dataRoot.toString());
    }

    private SkillService skillService(boolean enabled) {
        var properties =
            new AgentProperties(workspace.toString(), PermissionMode.READ_ONLY, 8, 3, 5, 300, 1, 0, 60_000, false);
        return new SkillService(new SkillProperties(enabled, null), properties, dataDir);
    }

    private Path skillDir(Path parent, String name, String body) throws IOException {
        Path dir = Files.createDirectories(parent.resolve(name));
        Files.writeString(dir.resolve("SKILL.md"), "---\ndescription: 技能 " + name + "。\n---\n" + body);
        return dir;
    }

    private SkillInstallService installer(SkillService skills) {
        return new SkillInstallService(skills, dataDir, (url, target) -> {
            throw new AssertionError("本用例不应触发 git clone");
        }, 10 * 60 * 1000L, 2000, 64L * 1024 * 1024, 500, 20L * 1024 * 1024);
    }

    @Test
    void previewsSingleSkillRepoLayout() throws IOException {
        Path repo = skillDir(source, "demo", "正文\n");
        Files.createDirectories(repo.resolve("references"));
        Files.writeString(repo.resolve("references/api.md"), "参考");

        var preview = installer(skillService(true)).preview(repo.toString(), null);

        assertEquals("local", preview.kind());
        assertEquals(1, preview.candidates().size());
        assertEquals("demo", preview.candidates().get(0).name());
        assertTrue(preview.candidates().get(0).valid());
        assertEquals("技能 demo。", preview.candidates().get(0).description());
    }

    @Test
    void previewsCollectionRepoLayoutWithInvalidEntries() throws IOException {
        skillDir(source, "alpha", "正文");
        skillDir(source, "beta", "正文");
        Path broken = Files.createDirectories(source.resolve("broken"));
        Files.writeString(broken.resolve("SKILL.md"), "---\n---\n没有 description");

        var preview = installer(skillService(true)).preview(source.toString(), null);

        assertEquals(3, preview.candidates().size());
        assertTrue(preview.candidates().stream()
            .filter(candidate -> candidate.name().equals("alpha") || candidate.name().equals("beta"))
            .allMatch(SkillInstallService.Candidate::valid));
        var invalid = preview.candidates().stream().filter(candidate -> !candidate.valid()).findFirst().orElseThrow();
        assertEquals("broken", invalid.name());
        assertTrue(invalid.reason().contains("description"));
    }

    @Test
    void rootSkillMdTakesPrecedenceOverSubdirectories() throws IOException {
        // 根有 SKILL.md：整个来源是单技能，名为来源目录名；子目录技能不再解析
        skillDir(source, "bundle", "根正文");
        skillDir(source.resolve("bundle"), "inner", "子正文");

        var preview = installer(skillService(true)).preview(source.resolve("bundle").toString(), null);

        assertEquals(1, preview.candidates().size());
        assertEquals("bundle", preview.candidates().get(0).name());
    }

    @Test
    void emptySourceYieldsGuidanceMessage() throws IOException {
        Path empty = Files.createDirectories(source.resolve("empty"));
        var preview = installer(skillService(true)).preview(empty.toString(), null);

        assertTrue(preview.candidates().isEmpty());
        assertTrue(preview.message().contains("没有找到技能"));
        assertTrue(preview.message().contains("skills/<名字>/SKILL.md"), "引导文案要提到包裹布局");
    }

    @Test
    void rejectsGithubWebPageUrlsWithGuidance() {
        var service = installer(skillService(true));
        // GitHub 网页里对着文件点「复制链接」拿到的是 blob 地址，git clone 只会报 repository
        // not found；诊断要把「这是网页地址、该用仓库根」说清楚
        var blob = assertThrows(SkillInstallService.InstallException.class, () -> service
            .preview("https://github.com/anthropics/skills/blob/main/skills/doc-coauthoring/SKILL.md", null));
        assertTrue(blob.getMessage().contains("网页地址"));
        assertTrue(blob.getMessage().contains("仓库根"));

        var tree = assertThrows(SkillInstallService.InstallException.class,
            () -> service.preview("https://github.com/u/repo/tree/main", null));
        assertTrue(tree.getMessage().contains("网页地址"));

        var raw = assertThrows(SkillInstallService.InstallException.class,
            () -> service.preview("https://raw.githubusercontent.com/u/repo/main/skills/x/SKILL.md", null));
        assertTrue(raw.getMessage().contains("文件"));
    }

    @Test
    void previewsSourcesLayoutRepositories() throws IOException {
        // anthropics/skills 形态：根无 SKILL.md，技能在 skills/<名>/ 下（sources 包裹布局）
        Path repo = Files.createDirectories(source.resolve("repo"));
        Files.createDirectories(repo.resolve("spec"));
        Files.writeString(repo.resolve("README.md"), "说明");
        skillDir(repo.resolve("skills"), "doc-coauthoring", "正文");
        skillDir(repo.resolve("skills"), "canvas-design", "正文");

        var preview = installer(skillService(true)).preview(repo.toString(), null);

        assertEquals(2, preview.candidates().size());
        assertTrue(preview.candidates().stream()
            .anyMatch(candidate -> candidate.name().equals("doc-coauthoring") && candidate.valid()));
        assertTrue(preview.candidates().stream()
            .anyMatch(candidate -> candidate.name().equals("canvas-design") && candidate.valid()));
    }

    @Test
    void singleAnonymousWrapperDirectoryIsAccepted() throws IOException {
        // 不是 skills/ 名字的唯一包裹目录也可以：只要恰好一个，就下钻它
        Path repo = Files.createDirectories(source.resolve("packed"));
        skillDir(repo.resolve("bundles"), "alpha", "正文");

        var preview = installer(skillService(true)).preview(repo.toString(), null);

        assertEquals(1, preview.candidates().size());
        assertEquals("alpha", preview.candidates().get(0).name());
    }

    @Test
    void ambiguousNestedLayoutsAreRejectedInsteadOfGuessed() throws IOException {
        // 没有名为 skills 的包裹目录时，多个匿名包裹意味着结构不明确，猜错会装进非预期的
        // 内容——报错引导改用本机目录导入
        Path repo = Files.createDirectories(source.resolve("nested"));
        skillDir(repo.resolve("bundles"), "alpha", "正文");
        skillDir(repo.resolve("more"), "beta", "正文");

        var thrown = assertThrows(SkillInstallService.InstallException.class,
            () -> installer(skillService(true)).preview(repo.toString(), null));
        assertTrue(thrown.getMessage().contains("多层嵌套"));
    }

    @Test
    void skillsConventionWinsOverSiblingWrappers() throws IOException {
        // skills/ 是既成惯例（anthropics/skills），同名兄弟包裹存在时按 skills/ 扫，
        // 不报歧义也不扫兄弟包裹
        Path repo = Files.createDirectories(source.resolve("mixed"));
        skillDir(repo.resolve("skills"), "alpha", "正文");
        skillDir(repo.resolve("more"), "beta", "正文");

        var preview = installer(skillService(true)).preview(repo.toString(), null);

        assertEquals(1, preview.candidates().size());
        assertEquals("alpha", preview.candidates().get(0).name());
    }

    @Test
    void installsSkillFromSourcesLayout() throws IOException {
        Path repo = Files.createDirectories(source.resolve("repo"));
        skillDir(repo.resolve("skills"), "doc-coauthoring", "正文");

        var service = installer(skillService(true));
        var preview = service.preview(repo.toString(), workspace.toString());
        var result = service.install(preview.token(), List.of("doc-coauthoring"), true, workspace.toString(), false);

        assertEquals("installed", result.results().get(0).status());
        assertTrue(Files.isRegularFile(workspace.resolve(".pilot/skills/doc-coauthoring/SKILL.md")));
    }

    @Test
    void rejectsMissingAndMalformedSources() {
        var service = installer(skillService(true));
        assertTrue(assertThrows(SkillInstallService.InstallException.class, () -> service.preview(null, null))
            .getMessage().contains("来源不能为空"));
        assertTrue(
            assertThrows(SkillInstallService.InstallException.class, () -> service.preview("github.com/u/repo", null))
                .getMessage().contains("http"));
        assertTrue(
            assertThrows(SkillInstallService.InstallException.class, () -> service.preview("skills/review", null))
                .getMessage().contains("绝对路径"));
    }

    @Test
    void rejectsInvalidRepoName() throws IOException {
        Path chinese = skillDir(source, "我的技能", "正文");
        assertTrue(assertThrows(SkillInstallService.InstallException.class,
            () -> installer(skillService(true)).preview(chinese.toString(), null)).getMessage().contains("来源名不合法"));
    }

    @Test
    void rejectsWhenSkillsDisabled() {
        var thrown = assertThrows(SkillInstallService.InstallException.class,
            () -> installer(skillService(false)).preview("https://example.com/x/review.git", null));
        assertTrue(thrown.getMessage().contains("技能功能已关闭"));
    }

    @Test
    void gitSourceStagesUnderRepoNameFromUrl() throws IOException {
        SkillInstallService.Cloner fake = (url, target) -> {
            Files.createDirectories(target.resolve(".git"));
            Files.createDirectories(target.resolve("scripts"));
            Files.writeString(target.resolve(".git/HEAD"), "ref: main");
            Files.writeString(target.resolve("SKILL.md"), "---\ndescription: git 技能。\n---\n正文");
            Files.writeString(target.resolve("scripts/run.py"), "print(1)");
        };
        var service = new SkillInstallService(skillService(true), dataDir, fake, 10 * 60 * 1000L, 2000,
            64L * 1024 * 1024, 500, 20L * 1024 * 1024);

        var preview = service.preview("https://github.com/user/pilot-skill-review.git", null);

        assertEquals("git", preview.kind());
        assertEquals("pilot-skill-review", preview.candidates().get(0).name());
        assertTrue(Files
            .isRegularFile(dataDir.file("tmp", "skill-install", preview.token(), "pilot-skill-review", "SKILL.md")));
    }

    @Test
    void cloneFailureSurfacesAndLeavesNoStage() throws IOException {
        SkillInstallService.Cloner failing = (url, target) -> {
            throw new SkillInstallService.InstallException("未找到 git，请安装它或改用本机目录导入");
        };
        var service = new SkillInstallService(skillService(true), dataDir, failing, 10 * 60 * 1000L, 2000,
            64L * 1024 * 1024, 500, 20L * 1024 * 1024);

        var thrown = assertThrows(SkillInstallService.InstallException.class,
            () -> service.preview("https://example.com/x/any.git", null));
        assertTrue(thrown.getMessage().contains("未找到 git"));
        try (var entries = Files.list(dataDir.file("tmp", "skill-install"))) {
            assertEquals(0, entries.count(), "失败的预览不应留下暂存目录");
        }
    }

    @Test
    void installsSkillIntoProjectAndUserRoots() throws IOException {
        skillDir(source, "alpha", "正文");
        skillDir(source, "beta", "正文");
        var service = installer(skillService(true));
        var preview = service.preview(source.toString(), workspace.toString());

        var result = service.install(preview.token(), List.of("alpha"), true, workspace.toString(), false);

        assertEquals("installed", result.results().get(0).status());
        assertTrue(Files.isRegularFile(workspace.resolve(".pilot/skills/alpha/SKILL.md")), "项目级落位");
        assertFalse(Files.exists(workspace.resolve(".pilot/skills/beta")), "未勾选的技能不装");

        var userResult = service.install(preview.token(), List.of("beta"), false, null, false);
        assertEquals("installed", userResult.results().get(0).status());
        assertTrue(Files.isRegularFile(dataDir.file("skills", "beta", "SKILL.md")), "用户级落位");
    }

    @Test
    void stripsGitMetadataDuringInstall() throws IOException {
        Path repo = skillDir(source, "cloned", "正文");
        Files.createDirectories(repo.resolve(".git/refs"));
        Files.createDirectories(repo.resolve("references"));
        Files.writeString(repo.resolve(".git/HEAD"), "ref: main");
        Files.writeString(repo.resolve("references/api.md"), "参考");

        var service = installer(skillService(true));
        var preview = service.preview(repo.toString(), workspace.toString());
        service.install(preview.token(), List.of("cloned"), true, workspace.toString(), false);

        Path installed = workspace.resolve(".pilot/skills/cloned");
        assertTrue(Files.isRegularFile(installed.resolve("SKILL.md")));
        assertTrue(Files.isRegularFile(installed.resolve("references/api.md")));
        assertFalse(Files.exists(installed.resolve(".git")), "git 元数据不进技能目录");
    }

    @Test
    void conflictByDefaultAndOverwriteReplaces() throws IOException {
        skillDir(source, "alpha", "新正文");
        Path existing = Files.createDirectories(workspace.resolve(".pilot/skills/alpha"));
        Files.writeString(existing.resolve("SKILL.md"), "旧内容");
        Files.writeString(existing.resolve("legacy.txt"), "旧附属文件");

        var service = installer(skillService(true));
        var preview = service.preview(source.toString(), workspace.toString());

        var conflict = service.install(preview.token(), List.of("alpha"), true, workspace.toString(), false);
        assertEquals("conflict", conflict.results().get(0).status());
        assertEquals("旧内容", Files.readString(existing.resolve("SKILL.md")), "冲突时不动旧目录");

        var overwrite = service.install(preview.token(), List.of("alpha"), true, workspace.toString(), true);
        assertEquals("installed", overwrite.results().get(0).status());
        assertTrue(Files.readString(existing.resolve("SKILL.md")).contains("新正文")
            && Files.readString(existing.resolve("SKILL.md")).contains("description"), "覆盖后是新技能文件（含 frontmatter）");
        assertFalse(Files.exists(existing.resolve("legacy.txt")), "覆盖 = 删旧装新，附属残留一并清除");
    }

    @Test
    void invalidAndUnknownNamesAreRejectedPerSkill() throws IOException {
        skillDir(source, "alpha", "正文");
        var service = installer(skillService(true));
        var preview = service.preview(source.toString(), workspace.toString());

        var result = service.install(preview.token(), List.of("alpha", "nope", ""), true, workspace.toString(), false);

        assertEquals("installed", result.results().get(0).status());
        assertEquals("invalid", result.results().get(1).status());
        assertEquals("invalid", result.results().get(2).status());
    }

    @Test
    void oversizedSkillIsRefusedAndTargetCleaned() throws IOException {
        Path repo = skillDir(source, "big", "正文");
        Files.writeString(repo.resolve("one.md"), "1");
        Files.writeString(repo.resolve("two.md"), "2");
        // 上限设为 2 个文件：SKILL.md + one.md 已满，two.md 触顶
        var service = new SkillInstallService(skillService(true), dataDir, (url, target) -> {
            throw new AssertionError("不应触发 git");
        }, 10 * 60 * 1000L, 2000, 64L * 1024 * 1024, 2, 20L * 1024 * 1024);

        var preview = service.preview(repo.toString(), workspace.toString());
        var result = service.install(preview.token(), List.of("big"), true, workspace.toString(), false);

        assertEquals("error", result.results().get(0).status());
        assertTrue(result.results().get(0).reason().contains("文件数超过上限"));
        assertFalse(Files.exists(workspace.resolve(".pilot/skills/big")), "装一半的目录不留残骸");
    }

    @Test
    void samePreviewSupportsMultiBatchInstalls() throws IOException {
        // 一次预览分两批：先项目级装一个，再用同一个 token 变更目标装另一个——
        // token 只由 TTL 回收，分批安装不应要求重新预览
        skillDir(source, "alpha", "正文");
        skillDir(source, "beta", "正文");
        var service = installer(skillService(true));
        var preview = service.preview(source.toString(), workspace.toString());

        assertEquals("installed", service.install(preview.token(), List.of("alpha"), true, workspace.toString(), false)
            .results().get(0).status());
        assertEquals("installed",
            service.install(preview.token(), List.of("beta"), false, null, false).results().get(0).status());

        assertTrue(Files.isRegularFile(workspace.resolve(".pilot/skills/alpha/SKILL.md")));
        assertTrue(Files.isRegularFile(dataDir.file("skills", "beta", "SKILL.md")));
    }

    @Test
    void expiredTokenIsRejectedAndSwept() throws IOException {
        skillDir(source, "alpha", "正文");
        var service = new SkillInstallService(skillService(true), dataDir, (url, target) -> {
            throw new AssertionError("不应触发 git");
        }, 0, 2000, 64L * 1024 * 1024, 500, 20L * 1024 * 1024);

        var preview = service.preview(source.toString(), workspace.toString());
        var thrown = assertThrows(SkillInstallService.InstallException.class,
            () -> service.install(preview.token(), List.of("alpha"), true, workspace.toString(), false));
        assertTrue(thrown.getMessage().contains("过期"));
        assertFalse(Files.exists(dataDir.file("tmp", "skill-install", preview.token())), "过期清扫应删除暂存目录");
    }

    @Test
    void installRejectsMissingSelectionAndUnknownToken() throws IOException {
        skillDir(source, "alpha", "正文");
        var service = installer(skillService(true));
        var preview = service.preview(source.toString(), workspace.toString());

        assertTrue(assertThrows(SkillInstallService.InstallException.class,
            () -> service.install(preview.token(), List.of(), true, workspace.toString(), false)).getMessage()
            .contains("请选择要安装的技能"));
        assertTrue(assertThrows(SkillInstallService.InstallException.class,
            () -> service.install("bogus-token", List.of("alpha"), true, workspace.toString(), false)).getMessage()
            .contains("过期"));
    }
}
