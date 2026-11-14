package com.pilot.server.run;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.pilot.server.workspace.RunSnapshotService;

/** 对话区的「本轮改动」卡依赖这个接口：run 粒度、逐文件状态与 diff。 */
@SpringBootTest(properties = "pilot.store=memory")
@AutoConfigureMockMvc
class RunChangesApiTest {
    private static final Path DATA = Path.of(System.getProperty("java.io.tmpdir"), "pilot-changes-api-test");

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("pilot.data.dir", DATA::toString);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RunSnapshotService snapshots;

    @TempDir
    Path workspace;

    @Test
    void listsFilesWrittenByThatRun() throws Exception {
        Path file = workspace.resolve("a.txt");
        Files.writeString(file, "hello\n");
        snapshots.bind("run_changes_1", workspace);
        snapshots.beforeWrite(file);
        snapshots.clear();

        mvc.perform(get("/api/runs/{runId}/changes", "run_changes_1")).andExpect(status().isOk())
            .andExpect(jsonPath("$.runId").value("run_changes_1"))
            .andExpect(jsonPath("$.workspace").value(workspace.toAbsolutePath().normalize().toString()))
            .andExpect(jsonPath("$.files[0].path").value("a.txt")).andExpect(jsonPath("$.files[0].status").value("M"));
    }

    @Test
    void comparesCompletedRunInOrdinaryDirectoryAndKeepsTheResult() throws Exception {
        String runId = "run_plain_" + UUID.randomUUID();
        Path edited = workspace.resolve("编辑.txt");
        Path created = workspace.resolve("新 文件.txt");
        Path deleted = workspace.resolve("delete.txt");
        Path unchanged = workspace.resolve("unchanged.txt");
        Files.writeString(edited, "之前\r\n");
        Files.writeString(deleted, "removed\n");
        Files.writeString(unchanged, "unchanged\n");

        snapshots.bind(runId, workspace);
        write(edited, "之后\r\n");
        write(created, "new\n");
        snapshots.beforeWrite(deleted);
        Files.delete(deleted);
        snapshots.afterWrite(deleted);
        write(unchanged, "unchanged\n");
        snapshots.finish();
        snapshots.clear();
        Files.writeString(edited, "later edit\n");

        mvc.perform(get("/api/runs/{runId}/changes", runId)).andExpect(status().isOk())
            .andExpect(jsonPath("$.snapshot").value(true)).andExpect(jsonPath("$.gitRepository").value(false))
            .andExpect(jsonPath("$.added").value(2)).andExpect(jsonPath("$.removed").value(2))
            .andExpect(jsonPath("$.files.length()").value(3))
            .andExpect(jsonPath("$.files[0].diff", containsString("-之前\n+之后\n")))
            .andExpect(jsonPath("$.files[0].diff", not(containsString("later edit"))))
            .andExpect(jsonPath("$.files[1].status").value("A"))
            .andExpect(jsonPath("$.files[1].diff", containsString("+new\n")))
            .andExpect(jsonPath("$.files[2].status").value("D"))
            .andExpect(jsonPath("$.files[2].diff", containsString("+++ /dev/null\n@@ -1,1 +0,0 @@\n-removed\n")));
    }

    @Test
    void comparesGitRunWithItsOwnStartingContents() throws Exception {
        assumeTrue(git("--version"), "需要本机可用的 git");
        Assertions.assertTrue(git("init", "-q"));
        Path file = workspace.resolve("a.txt");
        Files.writeString(file, "committed version\nold\n");
        Assertions.assertTrue(git("add", "."));
        Assertions.assertTrue(git("-c", "user.name=Pilot Test", "-c", "user.email=pilot@example.test", "-c",
            "commit.gpgsign=false", "commit", "-qm", "initial"));
        Files.writeString(file, "manual change before the run\nold\n");

        String runId = "run_git_" + UUID.randomUUID();
        snapshots.bind(runId, workspace);
        write(file, "manual change before the run\nnew\n");
        snapshots.finish();
        snapshots.clear();

        mvc.perform(get("/api/runs/{runId}/changes", runId)).andExpect(status().isOk())
            .andExpect(jsonPath("$.snapshot").value(true)).andExpect(jsonPath("$.gitRepository").value(true))
            .andExpect(jsonPath("$.added").value(1)).andExpect(jsonPath("$.removed").value(1))
            .andExpect(jsonPath("$.files[0].diff", containsString(" manual change before the run\n-old\n+new\n")))
            .andExpect(jsonPath("$.files[0].diff", not(containsString("committed version"))));
    }

    @Test
    void rollbackEndpointReportsConflictAndKeepsManualEdit() throws Exception {
        Path file = workspace.resolve("a.txt");
        Files.writeString(file, "original");
        snapshots.bind("run_rollback_conflict", workspace);
        snapshots.beforeWrite(file);
        Files.writeString(file, "agent result");
        snapshots.finish();
        snapshots.clear();
        Files.writeString(file, "manual edit");

        mvc.perform(post("/api/runs/{runId}/rollback", "run_rollback_conflict")).andExpect(status().isOk())
            .andExpect(jsonPath("$.conflicts[0]").value("a.txt")).andExpect(jsonPath("$.restored").isEmpty())
            .andExpect(jsonPath("$.deleted").isEmpty());

        Assertions.assertEquals("manual edit", Files.readString(file));
    }

    @Test
    void returnsEmptyReportWhenRunHasNoSnapshot() throws Exception {
        mvc.perform(get("/api/runs/{runId}/changes", "run_without_snapshot")).andExpect(status().isOk())
            .andExpect(jsonPath("$.files").isEmpty()).andExpect(jsonPath("$.added").value(0));
    }

    private void write(Path path, String content) throws Exception {
        snapshots.beforeWrite(path);
        Files.writeString(path, content);
        snapshots.afterWrite(path);
    }

    private boolean git(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        try {
            return new ProcessBuilder(command).directory(workspace.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                .waitFor() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            return false;
        }
    }
}
