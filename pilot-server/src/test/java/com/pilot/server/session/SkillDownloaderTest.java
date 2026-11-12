package com.pilot.server.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillDownloaderTest {
    @TempDir
    Path temp;

    private void archive(Path file, Map<String, String> entries) throws IOException {
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    @Test
    void archiveKeepsWholeSelectedSkillAndUsesRefWithoutGit() throws Exception {
        var downloader = new SkillDownloader((url, target) -> fail("不应调用 Git"), (url, target) -> {
            assertEquals("https://codeload.github.com/team/repo/zip/release%2Fv2", url.toString());
            archive(target,
                Map.of("repo-release/skills/pdf/SKILL.md", "instructions", "repo-release/skills/pdf/references/api.md",
                    "reference", "repo-release/skills/pdf/scripts/check.py", "print('ok')",
                    "repo-release/skills/other/SKILL.md", "other"));
        });
        Path target = temp.resolve("source");
        downloader.clone("https://github.com/team/repo.git", target, "release/v2", "skills/pdf");
        assertEquals("reference", Files.readString(target.resolve("skills/pdf/references/api.md")));
        assertTrue(Files.exists(target.resolve("skills/pdf/scripts/check.py")));
        assertFalse(Files.exists(target.resolve("skills/other")));
        assertFalse(Files.exists(temp.resolve("download.zip")));
    }

    @Test
    void failedDownloadFallsBackWithTheSameRefAndSelection() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SkillInstallService.Cloner git = new SkillInstallService.Cloner() {
            public void clone(String url, Path target) {
                fail("必须保留 ref 和 path");
            }

            public void clone(String url, Path target, String ref, String path) {
                assertEquals("v2", ref);
                assertEquals("skills/pdf", path);
                calls.incrementAndGet();
            }
        };
        new SkillDownloader(git, (url, target) -> {
            throw new IOException("HTTP 403");
        }).clone("https://github.com/team/repo", temp.resolve("source"), "v2", "skills/pdf");
        assertEquals(1, calls.get());
    }

    @Test
    void interruptedDownloadDoesNotStartFallback() {
        var downloader = new SkillDownloader((url, target) -> fail("取消后不能继续克隆"), (url, target) -> {
            throw new InterruptedException();
        });
        assertThrows(InterruptedException.class,
            () -> downloader.clone("https://github.com/team/repo", temp.resolve("source")));
    }

    @Test
    void rejectsTraversalMultipleRootsDuplicateDestinationsAndMissingDirectory() throws IOException {
        var cases = new ArrayList<Map<String, String>>();
        cases.add(Map.of("repo/../escape", "x"));
        cases.add(Map.of("/repo/absolute", "x"));
        cases.add(Map.of("repo/C:/escape", "x"));
        cases.add(Map.of("repo/a", "x", "other/b", "x"));
        var aliases = new LinkedHashMap<String, String>();
        aliases.put("repo/x", "first");
        aliases.put("repo/./x", "second");
        cases.add(aliases);
        for (int i = 0; i < cases.size(); i++) {
            Path archive = temp.resolve("bad" + i + ".zip");
            archive(archive, cases.get(i));
            Path target = temp.resolve("out" + i);
            assertThrows(IOException.class, () -> SkillDownloader.extract(archive, target, ""));
        }
        Path missing = temp.resolve("missing.zip");
        archive(missing, Map.of("repo/other/SKILL.md", "x"));
        assertThrows(IOException.class, () -> SkillDownloader.extract(missing, temp.resolve("out"), "skills/pdf"));
        assertFalse(Files.exists(temp.resolve("escape")));
    }

    @Test
    void rejectsExpandedArchiveBudgetWithoutFallback() throws IOException {
        Path archive = temp.resolve("large.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("repo/huge"));
            byte[] block = new byte[1024 * 1024];
            for (int i = 0; i < 65; i++) {
                zip.write(block);
            }
            zip.closeEntry();
        }
        assertThrows(IOException.class, () -> SkillDownloader.extract(archive, temp.resolve("out"), ""));
    }
}
