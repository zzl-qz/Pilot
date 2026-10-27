package com.pilot.server.controller;

import com.pilot.server.workspace.RunSnapshotService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 对话区的「本轮改动」卡依赖这个接口：run 粒度、逐文件状态与 diff。 */
@SpringBootTest(properties = {"pilot.mock.enabled=false", "pilot.agent.mode=mock", "pilot.store=memory"})
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

        mvc.perform(get("/api/runs/{runId}/changes", "run_changes_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run_changes_1"))
                .andExpect(jsonPath("$.workspace").value(workspace.toAbsolutePath().normalize().toString()))
                .andExpect(jsonPath("$.files[0].path").value("a.txt"))
                .andExpect(jsonPath("$.files[0].status").value("M"));
    }

    @Test
    void returnsEmptyReportWhenRunHasNoSnapshot() throws Exception {
        mvc.perform(get("/api/runs/{runId}/changes", "run_without_snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files").isEmpty())
                .andExpect(jsonPath("$.added").value(0));
    }
}
