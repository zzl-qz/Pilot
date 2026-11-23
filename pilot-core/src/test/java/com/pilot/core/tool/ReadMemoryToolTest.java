package com.pilot.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.memory.MemoryStore;
import com.pilot.core.memory.MemoryType;

class ReadMemoryToolTest {
    @TempDir
    Path temp;

    @Test
    void readsProjectAndUserTopicEntriesAndValidatesSlug() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("ws"));
        MemoryStore store = new MemoryStore(temp.resolve("data/memory"));
        store.saveEntry(workspace, MemoryStore.SCOPE_PROJECT, null, MemoryType.PROJECT, "Use SQLite",
            "Use real SQLite in integration tests", "Avoid H2 dialect drift");
        store.saveEntry(workspace, MemoryStore.SCOPE_USER, null, MemoryType.USER, "Go Background", "User knows Go well",
            "");

        ReadMemoryTool tool = new ReadMemoryTool(store, workspace);
        assertTrue(tool.readOnly());
        assertTrue(tool.parallelSafe());

        String project = tool.execute(Map.of("slug", "use-sqlite"));
        assertTrue(project.contains("scope=project"));
        assertTrue(project.contains("Avoid H2 dialect drift"));

        String user = tool.execute(Map.of("slug", "go-background", "scope", "user"));
        assertTrue(user.contains("scope=user"));
        assertTrue(user.contains("User knows Go well"));

        String invalid = tool.execute(Map.of("slug", "../escape"));
        assertTrue(invalid.startsWith("ERROR:"));
        assertEquals("ERROR: 未找到记忆条目 missing-slug", tool.execute(Map.of("slug", "missing-slug")));
        assertTrue(tool.execute(Map.of()).startsWith("ERROR:"));
    }

    @Test
    void addsStalenessCaveatWhenAgedAndSupportsKeywordSearch() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("ws"));
        MemoryStore store = new MemoryStore(temp.resolve("data/memory"));
        store.saveEntry(workspace, MemoryStore.SCOPE_PROJECT, null, MemoryType.PROJECT, "Cache Key",
            "Keep cache key grouped by sessionId", "Maximize prefix hits");
        store.saveEntry(workspace, MemoryStore.SCOPE_USER, null, MemoryType.FEEDBACK, "Style Guide",
            "Prefer early returns", "");

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Clock freshClock = Clock.fixed(today.plusDays(3).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        ReadMemoryTool freshTool = new ReadMemoryTool(store, workspace, freshClock);
        assertFalse(freshTool.execute(Map.of("slug", "cache-key")).contains("提示：该记忆更新于"));

        Clock staleClock = Clock.fixed(today.plusDays(10).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        ReadMemoryTool staleTool = new ReadMemoryTool(store, workspace, staleClock);
        String staleOutput = staleTool.execute(Map.of("slug", "cache-key"));
        assertTrue(staleOutput.contains("提示：该记忆更新于"));
        assertTrue(staleOutput.contains("距今 10 天"));

        String searchHit = staleTool.execute(Map.of("query", "prefix"));
        assertTrue(searchHit.contains("[cache-key]"));
        assertFalse(searchHit.contains("[style-guide]"));

        String byType = staleTool.execute(Map.of("type", "feedback"));
        assertTrue(byType.contains("[style-guide]"));
        assertEquals("未找到匹配的记忆条目。", staleTool.execute(Map.of("query", "no-such-term")));
    }
}
