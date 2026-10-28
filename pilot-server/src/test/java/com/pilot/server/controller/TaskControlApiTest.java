package com.pilot.server.controller;

import com.pilot.core.session.SessionService;
import com.pilot.server.session.RunStarter;
import com.pilot.server.session.TaskControlService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "pilot.store=memory")
@AutoConfigureMockMvc
class TaskControlApiTest {
    private static final Path DATA = Path.of(System.getProperty("java.io.tmpdir"), "pilot-task-api-" + UUID.randomUUID());
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { registry.add("pilot.data.dir", DATA::toString); }
    @Autowired MockMvc mvc;
    @Autowired SessionService sessions;
    @Autowired RunStarter runs;
    @Autowired TaskControlService tasks;

    @Test
    void absentTaskIsJsonNullAndUnknownSessionIsNotFound() throws Exception {
        String id = sessions.createSession("任务测试").id();
        mvc.perform(get("/api/sessions/{id}/task", id)).andExpect(status().isOk()).andExpect(content().string("null"));
        mvc.perform(get("/api/sessions/{id}/task", "missing")).andExpect(status().isNotFound());
    }

    @Test
    void updatePreservesOriginalGoalAndRequiresEvidence() throws Exception {
        String id = sessions.createSession("任务测试").id();
        tasks.start(id, "原始目标", List.of(), List.of(new TaskControlService.Acceptance("测试通过", false, "")));
        tasks.finish(id);
        mvc.perform(put("/api/sessions/{id}/task", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"constraints\":[\"只改客户端\"],\"acceptance\":[{\"text\":\"测试通过\",\"checked\":true,\"evidence\":\"\"}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/sessions/{id}/task", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originalGoal\":\"不可覆盖\",\"constraints\":[\"只改客户端\"],\"acceptance\":[{\"text\":\"测试通过\",\"checked\":true,\"evidence\":\"重连测试通过\"}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.originalGoal").value("原始目标"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        assertTrue(runs.activeRun(id).isEmpty());
    }

    @Test
    void activeTaskCannotBeEditedThroughRequirementsEndpoint() throws Exception {
        String id = sessions.createSession("任务测试").id();
        tasks.start(id, "原始目标", List.of(), List.of());
        String slot = runs.claimRun(id).orElseThrow();
        try {
            mvc.perform(put("/api/sessions/{id}/task", id).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"constraints\":[],\"acceptance\":[]}"))
                    .andExpect(status().isConflict());
        } finally { runs.abandon(slot); }
        assertEquals("原始目标", tasks.load(id).originalGoal());
    }
}
