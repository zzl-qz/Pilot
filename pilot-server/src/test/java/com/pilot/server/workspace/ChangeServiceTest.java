package com.pilot.server.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ChangeServiceTest {
    @TempDir
    Path workspace;

    private final ChangeService changes = new ChangeService();

    @Test
    void listsTouchedFilesEvenWithoutGit() throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "hello");

        ChangeService.RunChangeReport report = changes.inspectRun("run_1", workspace,
                List.of("a.txt", "missing.txt", "../escape.txt", "./a.txt"));

        assertFalse(report.gitRepository());
        assertEquals(List.of("a.txt", "missing.txt"),
                report.files().stream().map(ChangeService.RunFileChange::path).toList(),
                "越界路径必须被剔除，重复路径只保留一次");
        assertEquals("M", report.files().get(0).status());
        assertEquals("D", report.files().get(1).status());
        assertEquals(0, report.added());
        assertTrue(report.files().stream().allMatch(file -> file.diff().isEmpty()));
    }

    @Test
    void reportsLineStatsAndDiffForEachFile() throws IOException {
        assumeTrue(git("--version"), "需要本机可用的 git");
        git("init", "-q");
        git("config", "core.autocrlf", "false");
        Path file = workspace.resolve("a.txt");
        Files.writeString(file, "one\ntwo\n");
        git("add", ".");
        commit();

        Files.writeString(file, "one\nTWO\nthree\n");
        Files.writeString(workspace.resolve("new.txt"), "hello\nworld\n");

        ChangeService.RunChangeReport report = changes.inspectRun("run_1", workspace,
                List.of("a.txt", "new.txt"));

        assertTrue(report.gitRepository());
        ChangeService.RunFileChange modified = file(report, "a.txt");
        assertEquals("M", modified.status());
        assertEquals(2, modified.added(), "TWO 与新增的 three");
        assertEquals(1, modified.removed(), "被替换掉的 two");
        assertTrue(modified.diff().contains("+TWO"), modified.diff());

        ChangeService.RunFileChange created = file(report, "new.txt");
        assertEquals("??", created.status());
        assertEquals(2, created.added(), "新增文件按当前内容合成全新增的 diff");
        assertEquals(0, created.removed());
        assertTrue(created.diff().contains("+hello"), created.diff());

        assertEquals(4, report.added());
        assertEquals(1, report.removed());
    }

    @Test
    void skipsFilesThatWereWrittenButEndUpUnchanged() throws IOException {
        assumeTrue(git("--version"), "需要本机可用的 git");
        git("init", "-q");
        git("config", "core.autocrlf", "false");
        Files.writeString(workspace.resolve("a.txt"), "same\n");
        git("add", ".");
        commit();

        ChangeService.RunChangeReport report = changes.inspectRun("run_1", workspace, List.of("a.txt"));

        assertNotNull(report);
        assertTrue(report.files().isEmpty(), "内容回到 HEAD 一致时不应算作本轮改动");
    }

    @Test
    void handlesDeletionRenameBinaryAndSpecialPaths() throws IOException {
        assumeTrue(git("--version"), "需要本机可用的 git");
        git("init", "-q");
        git("config", "core.autocrlf", "false");
        Files.writeString(workspace.resolve("old name.txt"), "old\n");
        Files.writeString(workspace.resolve("删除.txt"), "before\n");
        Files.write(workspace.resolve("image.bin"), new byte[]{0, 1, 2, 3});
        git("add", ".");
        commit();

        Files.delete(workspace.resolve("old name.txt"));
        Files.writeString(workspace.resolve("new name.txt"), "old\n");
        Files.writeString(workspace.resolve("删除.txt"), "after\n");
        Files.write(workspace.resolve("image.bin"), new byte[]{0, 1, 2, 4});

        ChangeService.RunChangeReport report = changes.inspectRun("run_1", workspace,
                List.of("old name.txt", "new name.txt", "删除.txt", "image.bin"));

        assertEquals("D", file(report, "old name.txt").status());
        assertEquals("??", file(report, "new name.txt").status());
        assertTrue(file(report, "删除.txt").diff().contains("+after"));
        assertTrue(file(report, "image.bin").diff().isEmpty(), "二进制内容不应被误呈现为文本");
    }

    @Test
    void capsNumberOfReportedFiles() throws IOException {
        assumeTrue(git("--version"), "需要本机可用的 git");
        git("init", "-q");
        var paths = new java.util.ArrayList<String>();
        for (int i = 0; i < 510; i++) {
            String path = "f" + i + ".txt";
            paths.add(path);
            Files.writeString(workspace.resolve(path), "x\n");
        }

        ChangeService.RunChangeReport report = changes.inspectRun("run_1", workspace, paths);

        assertEquals(500, report.files().size());
        assertTrue(report.truncated());
    }

    /** 源码里以 {@code --} / {@code ++} 开头的行在补丁里同样以 {@code ---}/{@code +++} 开头，不能被当成文件头跳过。 */
    @Test
    void countsLinesWhoseContentLooksLikeDiffHeaders() throws IOException {
        assumeTrue(git("--version"), "需要本机可用的 git");
        git("init", "-q");
        git("config", "core.autocrlf", "false");
        Path file = workspace.resolve("notes.txt");
        Files.writeString(file, "a\n-- dash\nb\n");
        git("add", ".");
        commit();

        Files.writeString(file, "a\n++ plus\nb\n");

        ChangeService.RunFileChange change = file(changes.inspectRun("run_1", workspace,
                List.of("notes.txt")), "notes.txt");

        assertEquals(1, change.added(), change.diff());
        assertEquals(1, change.removed(), change.diff());
    }

    /** 已 git add 的改动不在 {@code git diff} 里，必须与 HEAD 比较才能看见。 */
    @Test
    void seesStagedChanges() throws IOException {
        assumeTrue(git("--version"), "需要本机可用的 git");
        git("init", "-q");
        git("config", "core.autocrlf", "false");
        Files.writeString(workspace.resolve("existing.txt"), "old\n");
        git("add", ".");
        commit();

        Files.writeString(workspace.resolve("staged.txt"), "x\ny\n");
        Files.writeString(workspace.resolve("existing.txt"), "old\nnew\n");
        git("add", ".");

        ChangeService.RunChangeReport report = changes.inspectRun("run_1", workspace,
                List.of("staged.txt", "existing.txt"));

        ChangeService.RunFileChange added = file(report, "staged.txt");
        assertEquals("A", added.status());
        assertEquals(2, added.added(), added.diff());
        assertEquals(1, file(report, "existing.txt").added());
    }

    /** 还没有任何提交的仓库：没有 HEAD 可比，也要列出文件而不是整体报空。 */
    @Test
    void worksInRepositoryWithoutAnyCommit() throws IOException {
        assumeTrue(git("--version"), "需要本机可用的 git");
        git("init", "-q");
        Files.writeString(workspace.resolve("committed-later.txt"), "hi\n");
        git("add", "committed-later.txt");
        Files.writeString(workspace.resolve("untracked.txt"), "x\n");

        ChangeService.RunChangeReport report = changes.inspectRun("run_1", workspace,
                List.of("committed-later.txt", "untracked.txt"));

        assertTrue(report.gitRepository());
        assertEquals(List.of("committed-later.txt", "untracked.txt"),
                report.files().stream().map(ChangeService.RunFileChange::path).toList());
        assertEquals(1, file(report, "committed-later.txt").added(), "暂存的新文件按当前内容合成 diff");
        assertEquals(1, file(report, "untracked.txt").added());
    }

    /** 重命名的补丁头是 {@code a/旧路径 b/新路径}，必须能对回新路径。 */
    @Test
    void reportsRenamedFileWithItsPatch() throws IOException {
        assumeTrue(git("--version"), "需要本机可用的 git");
        git("init", "-q");
        git("config", "core.autocrlf", "false");
        Files.writeString(workspace.resolve("a.txt"), "one\ntwo\nthree\n");
        git("add", ".");
        commit();

        git("mv", "a.txt", "b.txt");
        Files.writeString(workspace.resolve("b.txt"), "one\nTWO\nthree\n");

        ChangeService.RunFileChange change = file(changes.inspectRun("run_1", workspace,
                List.of("b.txt")), "b.txt");

        assertEquals("R", change.status());
        assertTrue(change.diff().contains("rename from a.txt"), change.diff());
        assertEquals(1, change.added(), change.diff());
        assertEquals(1, change.removed(), change.diff());
    }

    /** 带空格的文件名在 pathspec 与 {@code diff --git} 头里都容易被切错。 */
    @Test
    void handlesTrackedPathWithSpaces() throws IOException {
        assumeTrue(git("--version"), "需要本机可用的 git");
        git("init", "-q");
        git("config", "core.autocrlf", "false");
        Path file = workspace.resolve("a b.txt");
        Files.writeString(file, "one\n");
        git("add", ".");
        commit();

        Files.writeString(file, "one\ntwo\n");

        ChangeService.RunFileChange change = file(changes.inspectRun("run_1", workspace,
                List.of("a b.txt")), "a b.txt");

        assertEquals("M", change.status());
        assertEquals(1, change.added(), change.diff());
    }

    /** 新增文件只展示前 400 行，但行数统计必须按整份文件算。 */
    @Test
    void keepsStatsAccurateWhenUntrackedDiffIsTruncated() throws IOException {
        assumeTrue(git("--version"), "需要本机可用的 git");
        git("init", "-q");
        int total = 1000;
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < total; i++) {
            content.append("line ").append(i).append('\n');
        }
        Files.writeString(workspace.resolve("big-new.txt"), content);

        ChangeService.RunChangeReport report = changes.inspectRun("run_1", workspace, List.of("big-new.txt"));
        ChangeService.RunFileChange change = file(report, "big-new.txt");

        assertEquals(total, change.added());
        assertTrue(report.truncated());
        assertTrue(change.diff().contains("… 已省略 600 行 …"), change.diff().substring(0, 200));
        assertFalse(change.diff().contains("+line 400\n"), "超出上限的行不应出现在 diff 里");
    }

    /** 单个文件的补丁超过上限时截断展示，统计仍按完整补丁算。 */
    @Test
    void truncatesHugeTrackedDiff() throws IOException {
        assumeTrue(git("--version"), "需要本机可用的 git");
        git("init", "-q");
        git("config", "core.autocrlf", "false");
        Path file = workspace.resolve("big.txt");
        Files.writeString(file, "old\n");
        git("add", ".");
        commit();

        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 2500; i++) {
            content.append("line ").append(i).append(" with some padding text\n");
        }
        Files.writeString(file, content);

        ChangeService.RunChangeReport report = changes.inspectRun("run_1", workspace, List.of("big.txt"));
        ChangeService.RunFileChange change = file(report, "big.txt");

        assertEquals(2500, change.added());
        assertEquals(1, change.removed());
        assertTrue(report.truncated());
        assertTrue(change.diff().length() <= 60_000 + 40, "单文件 diff 应被截断，实际 " + change.diff().length());
        assertTrue(change.diff().contains("… 该文件 diff 已截断 …"));
    }

    /** 空的新文件不应该被当成二进制或直接丢掉。 */
    @Test
    void keepsEmptyNewFile() throws IOException {
        assumeTrue(git("--version"), "需要本机可用的 git");
        git("init", "-q");
        Files.writeString(workspace.resolve("empty.txt"), "");

        ChangeService.RunChangeReport report = changes.inspectRun("run_1", workspace, List.of("empty.txt"));
        ChangeService.RunFileChange change = file(report, "empty.txt");

        assertEquals("??", change.status());
        assertEquals(0, change.added());
        assertTrue(change.diff().startsWith("diff --git a/empty.txt"), change.diff());
    }

    /** 会话级审阅面板与运行级同源：已暂存的改动也要出现在 diff 里。 */
    @Test
    void sessionReportIncludesStagedChanges() throws IOException {
        assumeTrue(git("--version"), "需要本机可用的 git");
        git("init", "-q");
        git("config", "core.autocrlf", "false");
        Files.writeString(workspace.resolve("a.txt"), "old\n");
        git("add", ".");
        commit();

        Files.writeString(workspace.resolve("a.txt"), "old\nnew\n");
        git("add", "a.txt");

        ChangeService.ChangeReport report = changes.inspect(workspace);

        assertTrue(report.gitRepository());
        assertTrue(report.diff().contains("+new"), report.diff());
    }

    private static ChangeService.RunFileChange file(ChangeService.RunChangeReport report, String path) {
        return report.files().stream()
                .filter(file -> file.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少文件: " + path));
    }

    private void commit() {
        git("-c", "user.name=pilot", "-c", "user.email=pilot@example.com", "commit", "-q", "-m", "init");
    }

    private boolean git(String... args) {
        var command = new java.util.ArrayList<String>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workspace.toFile());
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            process.getOutputStream().close();
            process.getInputStream().readAllBytes();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
