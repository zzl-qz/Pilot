package com.pilot.core.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryStoreTest {
    @TempDir
    Path temp;

    @Test
    void sharesProjectKeyAcrossGitRepoSubdirAndWorktree() throws IOException {
        Path mainRepo = Files.createDirectories(temp.resolve("repo-main"));
        Files.createDirectories(mainRepo.resolve(".git/worktrees/feat-a"));
        Files.writeString(mainRepo.resolve(".git/worktrees/feat-a/commondir"), "../..\n");
        Path subDir = Files.createDirectories(mainRepo.resolve("src/main"));

        Path worktree = Files.createDirectories(temp.resolve("repo-wt"));
        Files.writeString(worktree.resolve(".git"),
            "gitdir: " + mainRepo.resolve(".git/worktrees/feat-a").toAbsolutePath() + "\n");

        Path otherProject = Files.createDirectories(temp.resolve("other-project"));

        String mainKey = MemoryStore.projectKey(mainRepo);
        assertEquals(mainKey, MemoryStore.projectKey(subDir));
        assertEquals(mainKey, MemoryStore.projectKey(worktree));
        assertNotEquals(mainKey, MemoryStore.projectKey(otherProject));

        MemoryStore store = new MemoryStore(temp.resolve("data/memory"));
        MemoryStore.Draft draft = store.addDraft(worktree, MemoryType.PROJECT, "共享测试", "worktree 写入的候选", "验证跨目录共享");
        assertEquals(List.of(draft), store.listDrafts(mainRepo));
    }

    @Test
    void enforcesClosedFourTypesAndDeduplicatesIdenticalDrafts() throws IOException {
        Path workspace = Files.createDirectories(temp.resolve("ws"));
        MemoryStore store = new MemoryStore(temp.resolve("data/memory"));

        assertThrows(IllegalArgumentException.class, () -> MemoryType.parse("custom"));
        assertThrows(IllegalArgumentException.class, () -> MemoryType.parse("  "));
        assertEquals(MemoryType.REFERENCE, MemoryType.parse("Reference"));

        MemoryStore.Draft first = store.addDraft(workspace, MemoryType.FEEDBACK, "使用 Vitest", "单测改用 Vitest", "跑得更快");
        MemoryStore.Draft dup = store.addDraft(workspace, MemoryType.FEEDBACK, "换个标题", "单测改用\nVitest", "原因不同也去重");
        assertEquals(first.id(), dup.id());
        assertEquals(1, store.listDrafts(workspace).size());
        assertFalse(Files.exists(workspace.resolve(".pilot/memory-draft.md")), "外置记忆绝不写进目标工作区");
    }

    @Test
    void approvesDraftToTopicFileRebuildsIndexAndSupportsCrud() throws IOException {
        Path workspace = Files.createDirectories(temp.resolve("ws"));
        MemoryStore store = new MemoryStore(temp.resolve("data/memory"));
        MemoryStore.Draft draft = store.addDraft(workspace, MemoryType.FEEDBACK, "Prefer Vitest",
            "Run vitest instead of jest", "Faster feedback");

        MemoryStore.Entry entry =
            store.approveDraft(workspace, draft.id(), MemoryStore.SCOPE_PROJECT, null, null, null, null);
        assertEquals("prefer-vitest", entry.slug());
        assertEquals("feedback", entry.type());
        assertTrue(entry.body().contains("Why: Faster feedback"));
        assertTrue(store.listDrafts(workspace).isEmpty());

        MemoryStore.IndexView index = store.loadIndex(workspace, MemoryStore.SCOPE_PROJECT);
        assertTrue(index.text().contains("[prefer-vitest]"));
        assertTrue(index.text().contains("(feedback)"));
        assertFalse(index.truncated());

        MemoryStore.Entry updated = store.saveEntry(workspace, MemoryStore.SCOPE_PROJECT, entry.slug(),
            MemoryType.PROJECT, "Vitest Standard", "Use pnpm vitest run", "CI standard");
        assertEquals("project", updated.type());
        assertTrue(store.readEntry(workspace, null, entry.slug()).orElseThrow().body().contains("Use pnpm vitest run"));

        store.deleteEntry(workspace, MemoryStore.SCOPE_PROJECT, entry.slug());
        assertTrue(store.readEntry(workspace, null, entry.slug()).isEmpty());
        assertEquals("", store.loadIndex(workspace, MemoryStore.SCOPE_PROJECT).text());
        assertThrows(NoSuchElementException.class,
            () -> store.deleteEntry(workspace, MemoryStore.SCOPE_PROJECT, entry.slug()));
    }

    @Test
    void enforcesLineAndCharacterCapsOnMemoryIndex() throws IOException {
        Path workspace = Files.createDirectories(temp.resolve("ws"));
        MemoryStore store = new MemoryStore(temp.resolve("data/memory"));
        Path projectDir = store.projectDir(workspace);
        Files.createDirectories(projectDir);

        StringBuilder manyLines = new StringBuilder();
        for (int i = 0; i < 230; i++) {
            manyLines.append("- [k").append(i).append("] (project) item ").append(i).append('\n');
        }
        Files.writeString(projectDir.resolve(MemoryStore.INDEX_FILE), manyLines.toString());
        MemoryStore.IndexView lineClipped = store.loadIndex(workspace, MemoryStore.SCOPE_PROJECT);
        assertTrue(lineClipped.truncated());
        assertTrue(lineClipped.text().contains("已截断"));
        assertTrue(lineClipped.text().lines().count() <= MemoryStore.MAX_INDEX_LINES + 1);

        Files.writeString(projectDir.resolve(MemoryStore.INDEX_FILE), "记".repeat(5_000));
        MemoryStore.IndexView charClipped = store.loadIndex(workspace, MemoryStore.SCOPE_PROJECT);
        assertTrue(charClipped.truncated());
        assertTrue(charClipped.text().length() <= MemoryStore.MAX_INDEX_CHARS + 30);
    }

    @Test
    void rejectsTraversalSlugsAndSymlinks() throws IOException {
        Path workspace = Files.createDirectories(temp.resolve("ws"));
        MemoryStore store = new MemoryStore(temp.resolve("data/memory"));

        assertThrows(IllegalArgumentException.class, () -> MemoryStore.validateSlug("../etc"));
        assertThrows(IllegalArgumentException.class, () -> MemoryStore.validateSlug("a/b"));
        assertThrows(IllegalArgumentException.class, () -> MemoryStore.validateSlug("UPPER"));
        assertThrows(IllegalArgumentException.class, () -> MemoryStore.validateSlug("foo\0bar"));

        Path topicsDir = store.projectDir(workspace).resolve(MemoryStore.TOPICS_DIR);
        Files.createDirectories(topicsDir);
        Path outside = Files.writeString(temp.resolve("secret.md"), "secret");
        Path link = topicsDir.resolve("bad-link.md");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | FileSystemException e) {
            Assumptions.assumeTrue(false, "当前系统不支持符号链接: " + e);
        }
        assertTrue(store.readEntry(workspace, MemoryStore.SCOPE_PROJECT, "bad-link").isEmpty());
        assertThrows(IllegalArgumentException.class,
            () -> store.deleteEntry(workspace, MemoryStore.SCOPE_PROJECT, "bad-link"));
    }

    @Test
    void generatesSafeAsciiSlugsForNonAsciiTitles() throws IOException {
        Path workspace = Files.createDirectories(temp.resolve("ws"));
        MemoryStore store = new MemoryStore(temp.resolve("data/memory"));

        MemoryStore.Entry entry =
            store.saveEntry(workspace, MemoryStore.SCOPE_USER, null, MemoryType.USER, "中文标题", "偏好用中文沟通", "");
        assertTrue(entry.slug().startsWith("mem-"));
        assertTrue(store.readEntry(workspace, MemoryStore.SCOPE_USER, entry.slug()).isPresent());
    }

    @Test
    void searchesEntriesByQueryTypeAndScopeWithoutAlteringFiles() throws IOException {
        Path workspace = Files.createDirectories(temp.resolve("ws"));
        MemoryStore store = new MemoryStore(temp.resolve("data/memory"));
        store.saveEntry(workspace, MemoryStore.SCOPE_PROJECT, null, MemoryType.PROJECT, "SQLite Mode",
            "Use real SQLite WAL in tests", "");
        store.saveEntry(workspace, MemoryStore.SCOPE_PROJECT, null, MemoryType.FEEDBACK, "Vite Build",
            "Build bundle before jar", "");
        store.saveEntry(workspace, MemoryStore.SCOPE_USER, null, MemoryType.USER, "SQLite Fan",
            "Knows SQLite internals", "");

        List<MemoryStore.Entry> sqliteAll = store.searchEntries(workspace, null, "sqlite", null, 10);
        assertEquals(2, sqliteAll.size());

        List<MemoryStore.Entry> projectOnly =
            store.searchEntries(workspace, MemoryStore.SCOPE_PROJECT, "sqlite", null, 10);
        assertEquals(1, projectOnly.size());
        assertEquals("sqlite-mode", projectOnly.getFirst().slug());

        List<MemoryStore.Entry> typed = store.searchEntries(workspace, null, null, MemoryType.FEEDBACK, 10);
        assertEquals(1, typed.size());
        assertEquals("vite-build", typed.getFirst().slug());
    }

    @Test
    void detectsAndImportsLegacyDraftsNonDestructivelyWithDeduplication() throws IOException {
        Path workspace = Files.createDirectories(temp.resolve("ws"));
        MemoryStore store = new MemoryStore(temp.resolve("data/memory"));
        assertFalse(store.hasLegacyDraftFile(workspace));

        Path legacyFile = Files.createDirectories(workspace.resolve(".pilot")).resolve("memory-draft.md");
        String original = """
            # 记忆候选区

            - [2026-11-01] [project] 数据库迁移只能增量追加 (Why: 线上库已有数据)
            - [user] 团队默认全中文回复
            - 普通历史记录没有日期与分类
            """;
        Files.writeString(legacyFile, original);
        assertTrue(store.hasLegacyDraftFile(workspace));

        MemoryStore.ImportResult first = store.importLegacyDraftFile(workspace);
        assertTrue(first.sourceFound());
        assertEquals(3, first.imported());
        assertEquals(0, first.skipped());
        assertEquals(3, store.draftCount(workspace));
        assertEquals(original, Files.readString(legacyFile), "只读导入绝不能修改旧工作区文件");

        List<MemoryStore.Draft> drafts = store.listDrafts(workspace);
        assertEquals("project", drafts.get(0).type());
        assertEquals("线上库已有数据", drafts.get(0).why());
        assertEquals("user", drafts.get(1).type());
        assertEquals("feedback", drafts.get(2).type());

        MemoryStore.ImportResult second = store.importLegacyDraftFile(workspace);
        assertEquals(0, second.imported());
        assertEquals(3, second.skipped());
        assertEquals(3, store.draftCount(workspace));
    }

    @Test
    void multiTermWeightedSearchRanksTitleAndMultiHitAboveSingleBodyHit() throws IOException {
        Path workspace = Files.createDirectories(temp.resolve("ws-search"));
        MemoryStore store = new MemoryStore(temp.resolve("data-search/memory"));

        MemoryStore.Entry bodyOnly = store.saveEntry(workspace, MemoryStore.SCOPE_PROJECT, null, MemoryType.PROJECT,
            "发布检查清单", "普通发布注意事项", "发布前顺带提一句 maven 命令。");
        MemoryStore.Entry mavenTest = store.saveEntry(workspace, MemoryStore.SCOPE_PROJECT, null, MemoryType.FEEDBACK,
            "Maven 单元测试命令", "跑单测先装 pilot-core", "后端改动后用 Maven 跑单元测试，避免漏编译。");
        store.saveEntry(workspace, MemoryStore.SCOPE_PROJECT, null, MemoryType.USER, "主题配色", "深色主题", "喜欢暗色界面");

        // 多词中英文混合 + 中文简称「单测」匹配「单元测试」，且双词+标题命中排在仅正文单词命中之前
        List<MemoryStore.Entry> hits = store.searchEntries(workspace, null, "maven 单测", null, 10);
        assertEquals(2, hits.size());
        assertEquals(mavenTest.slug(), hits.get(0).slug());
        assertEquals(bodyOnly.slug(), hits.get(1).slug());

        // 长中文短语 2-gram 召回
        List<MemoryStore.Entry> phraseHits = store.searchEntries(workspace, null, "单元测试指令", null, 10);
        assertFalse(phraseHits.isEmpty());
        assertEquals(mavenTest.slug(), phraseHits.getFirst().slug());
    }
}
