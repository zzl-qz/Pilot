package com.pilot.server.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Git 审阅面板闭环测试：按选中文件暂存提交、多行中文提交说明、本地草稿与近期提交历史。 */
class GitCommitWorkflowTest {
    @TempDir
    Path repo;

    private final ChangeService service = new ChangeService();

    @Test
    void commitsSelectedFilesOnlyAndReturnsRecentLogAndDraft() throws Exception {
        git("init", "-b", "main");
        git("config", "user.name", "Pilot Tester");
        git("config", "user.email", "tester@pilot.local");
        Files.writeString(repo.resolve("README.md"), "# Demo\n", StandardCharsets.UTF_8);
        git("add", "README.md");
        git("commit", "-m", "初始化仓库");

        // 准备两个修改文件 + 一个未跟踪文件
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/A.java"), "class A {}\n", StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("src/B.java"), "class B {}\n", StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("README.md"), "# Demo updated\n", StandardCharsets.UTF_8);

        ChangeService.DraftMessageResult draft =
            service.draftCommitMessage(repo, List.of("src/A.java", "src/B.java"), false);
        assertEquals(2, draft.fileCount());
        assertTrue(draft.message().contains("新增 src 中的 2 个文件"), draft.message());
        assertTrue(draft.message().contains("src/A.java"), draft.message());

        // 仅提交 src/A.java，带引号/符号/多行中文提交说明，验证 README.md 与 src/B.java 留在工作区
        String multilineMsg = "新增 src/A.java（含 \"引号\" 与 $变量）\n\n- 保留其他未选中改动";
        ChangeService.CommitResult result = service.commit(repo, List.of("src/A.java"), false, multilineMsg);
        assertTrue(result.ok(), result.error());
        assertFalse(result.shortHash().isBlank());
        assertEquals("新增 src/A.java（含 \"引号\" 与 $变量）", result.subject());

        ChangeService.ChangeReport after = service.inspect(repo);
        List<String> remaining = after.files().stream().map(ChangeService.FileChange::path).toList();
        assertFalse(remaining.contains("src/A.java"), "已提交的文件应从待审阅改动中消失");
        assertTrue(remaining.contains("src/B.java"), "未选中的未跟踪文件必须保留");
        assertTrue(remaining.contains("README.md"), "未选中的修改文件必须保留");

        // 查看近期提交历史
        ChangeService.CommitLogReport log = service.log(repo, 10);
        assertTrue(log.gitRepository());
        assertEquals("main", log.branch());
        assertEquals(2, log.commits().size());
        assertEquals("新增 src/A.java（含 \"引号\" 与 $变量）", log.commits().get(0).subject());
        assertEquals("Pilot Tester", log.commits().get(0).author());
        assertEquals("初始化仓库", log.commits().get(1).subject());
    }

    @Test
    void rejectsBlankMessagePathTraversalAndNonGitWorkspace() throws Exception {
        assertFalse(service.log(repo, 5).gitRepository());
        assertFalse(service.commit(repo, List.of("a.txt"), false, "msg").ok());

        git("init", "-b", "main");
        git("config", "user.name", "Pilot Tester");
        git("config", "user.email", "tester@pilot.local");

        ChangeService.CommitResult blank = service.commit(repo, List.of("a.txt"), false, "   ");
        assertFalse(blank.ok());
        assertTrue(blank.error().contains("不能为空"));

        ChangeService.CommitResult escape = service.commit(repo, List.of("../outside.txt"), false, "bad");
        assertFalse(escape.ok());
        assertTrue(escape.error().contains("工作区内"));

        ChangeService.CommitResult emptySelection = service.commit(repo, List.of(), false, "no files");
        assertFalse(emptySelection.ok());
        assertTrue(emptySelection.error().contains("至少一个"));
    }

    @Test
    void supportsDiscardStashBranchesAndReportsSwitchConflicts() throws Exception {
        git("init", "-b", "main");
        git("config", "user.name", "Pilot Tester");
        git("config", "user.email", "tester@pilot.local");
        Files.writeString(repo.resolve("file.txt"), "base\n", StandardCharsets.UTF_8);
        git("add", "file.txt");
        git("commit", "-m", "base");
        git("switch", "-c", "feature");
        Files.writeString(repo.resolve("file.txt"), "feature\n", StandardCharsets.UTF_8);
        git("add", "file.txt");
        git("commit", "-m", "feature");
        git("switch", "main");

        assertTrue(service.branches(repo).branches().containsAll(List.of("main", "feature")));
        // 已暂存 + 未暂存并存时，放弃未暂存只回退工作区到暂存区内容，不抹掉暂存区
        Files.writeString(repo.resolve("file.txt"), "staged\n", StandardCharsets.UTF_8);
        git("add", "file.txt");
        assertFalse(service.discardUnstaged(repo, "file.txt").ok(), "仅暂存无未暂存修改时应拒绝");
        Files.writeString(repo.resolve("file.txt"), "unstaged edit\n", StandardCharsets.UTF_8);
        assertTrue(service.discardUnstaged(repo, "file.txt").ok());
        assertEquals("staged\n", Files.readString(repo.resolve("file.txt")));
        git("reset", "--hard", "HEAD");

        Files.writeString(repo.resolve("untracked.txt"), "new\n", StandardCharsets.UTF_8);
        assertFalse(service.discardUnstaged(repo, "untracked.txt").ok());
        Files.deleteIfExists(repo.resolve("untracked.txt"));
        assertFalse(service.discardUnstaged(repo, "../outside").ok());
        assertFalse(service.stashPush(repo, "empty").ok(), "干净工作区不应伪造 stash 成功");

        Files.writeString(repo.resolve("file.txt"), "stashed\n", StandardCharsets.UTF_8);
        assertTrue(service.stashPush(repo, "work in progress").ok());
        assertEquals("base\n", Files.readString(repo.resolve("file.txt")));
        assertEquals("stash@{0}", service.stashes(repo).stashes().getFirst().reference());
        assertFalse(service.stashPop(repo, "--index").ok());
        assertTrue(service.stashPop(repo, "stash@{0}").ok());
        assertEquals("stashed\n", Files.readString(repo.resolve("file.txt")));

        Files.writeString(repo.resolve("file.txt"), "main conflicting edit\n", StandardCharsets.UTF_8);
        ChangeService.GitOperationResult conflict = service.switchBranch(repo, "feature");
        assertFalse(conflict.ok());
        assertTrue(conflict.error().toLowerCase().contains("local changes") || conflict.error().contains("覆盖"),
            conflict.error());
        assertEquals("main", service.branches(repo).current());
        assertFalse(service.switchBranch(repo, "--help").ok());
        assertFalse(service.switchBranch(repo, "nonexistent-branch").ok());
        assertFalse(service.switchBranch(repo, "main\r\nfeature").ok());
    }

    private void git(String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        Process process = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), "git " + String.join(" ", args) + " failed: " + output);
    }
}
