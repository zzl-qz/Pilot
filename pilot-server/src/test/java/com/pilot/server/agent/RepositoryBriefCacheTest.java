package com.pilot.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryBriefCacheTest {
    @TempDir
    Path dir;

    @Test
    void returnsStoredTextWhenFingerprintMatches() {
        RepositoryBriefCache cache = new RepositoryBriefCache(dir);
        cache.store("abc", "fp-1", "## 仓库现状（快照）\n分支 `main`");

        assertEquals("## 仓库现状（快照）\n分支 `main`", cache.cached("abc", "fp-1"));
    }

    @Test
    void missesWhenFingerprintChanges() {
        RepositoryBriefCache cache = new RepositoryBriefCache(dir);
        cache.store("abc", "fp-1", "旧正文");

        assertNull(cache.cached("abc", "fp-2"), "指纹不一致必须重新生成，不能拿旧分支或旧脏文件清单");
        assertEquals("旧正文", cache.cached("abc", "fp-1"), "指纹一致时仍可用");
    }

    @Test
    void missesWhenFileIsMissingOrCorrupt() throws Exception {
        RepositoryBriefCache cache = new RepositoryBriefCache(dir);
        assertNull(cache.cached("never-written", "fp-1"), "没写过就是未命中");

        Files.writeString(dir.resolve("broken.txt"), "no-newline-separator");
        assertNull(cache.cached("broken", "no-newline-separator"), "没有指纹行按损坏处理");
    }

    @Test
    void overwritesPreviousEntryAndKeepsBodyLines() {
        RepositoryBriefCache cache = new RepositoryBriefCache(dir);
        cache.store("abc", "fp-1", "第一行\n第二行");
        cache.store("abc", "fp-2", "新正文\n带两行");

        assertEquals("新正文\n带两行", cache.cached("abc", "fp-2"), "覆盖后正文的换行必须原样保留");
        assertNull(cache.cached("abc", "fp-1"));
    }

    @Test
    void keyIsStablePerWorkspaceAndDistinguishesDifferentRoots() {
        String first = RepositoryBriefCache.key(dir);
        assertEquals(16, first.length());
        assertEquals(first, RepositoryBriefCache.key(dir), "同一路径每次都是同一个文件名");
        assertNotEquals(first, RepositoryBriefCache.key(dir.resolve("other")));
    }

    @Test
    void withoutDirectoryNeitherReadsNorThrows() {
        RepositoryBriefCache cache = new RepositoryBriefCache(null);
        cache.store("abc", "fp-1", "正文");

        assertNull(cache.cached("abc", "fp-1"), "数据目录缺失时静默当作未命中");
    }
}