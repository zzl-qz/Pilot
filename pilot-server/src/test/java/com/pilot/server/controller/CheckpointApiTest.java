package com.pilot.server.controller;

import com.pilot.core.event.AssistantDelta;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunStarted;
import com.pilot.core.loop.Message;
import com.pilot.core.plan.PlanItem;
import com.pilot.core.session.ConversationRepository;
import com.pilot.core.session.PlanRepository;
import com.pilot.core.session.SessionService;
import com.pilot.server.session.RunStarter;
import com.pilot.server.workspace.RunSnapshotService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "pilot.store=memory")
@AutoConfigureMockMvc
class CheckpointApiTest {
    private static final Path DATA = Path.of(System.getProperty("java.io.tmpdir"), "pilot-checkpoints-" + UUID.randomUUID());
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { registry.add("pilot.data.dir", DATA::toString); }

    @Autowired MockMvc mvc;
    @Autowired SessionService sessions;
    @Autowired RunSnapshotService snapshots;
    @Autowired ConversationRepository conversations;
    @Autowired PlanRepository plans;
    @Autowired RunStarter runs;
    @Autowired ObjectMapper mapper;
    @TempDir Path workspace;

    @Test
    void codeOnlyRestoresMultipleRunsAndKeepsConversation() throws Exception {
        Fixture f = fixture();
        mvc.perform(get("/api/sessions/{id}/checkpoints", f.id).param("sequence", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.runs").value(2))
                .andExpect(jsonPath("$.code.ready").value(true))
                .andExpect(jsonPath("$.conversationAvailable").value(true));
        long count = sessions.eventCount(f.id);
        var memory = conversations.load(f.id);
        restore(f, 1, "code").andExpect(jsonPath("$.applied").value(true));
        assertEquals("before", Files.readString(f.file));
        assertEquals(count, sessions.eventCount(f.id));
        assertEquals(memory, conversations.load(f.id));
    }

    @Test
    void conversationOnlyUsesExactCheckpointAndKeepsManualCode() throws Exception {
        Fixture f = fixture();
        Files.writeString(f.file, "manual");
        restore(f, f.secondSequence, "conversation").andExpect(jsonPath("$.applied").value(true));
        assertEquals("manual", Files.readString(f.file));
        assertEquals(f.firstMemory, conversations.load(f.id), "重复输入不能导致回到第一条同名消息");
        assertEquals(3, sessions.eventCount(f.id));
        assertEquals(List.of(new PlanItem("原计划", PlanItem.COMPLETED)), plans.load(f.id));
        assertTrue(runs.activeRun(f.id).isEmpty());
    }

    @Test
    void combinedRestoreIsAllOrNothingOnConflictAndRestoresBothWhenSafe() throws Exception {
        Fixture f = fixture();
        Files.writeString(f.file, "manual");
        var memory = conversations.load(f.id);
        restore(f, 1, "both").andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.code.conflicts[0]").value("a.txt"));
        assertEquals(6, sessions.eventCount(f.id));
        assertEquals(memory, conversations.load(f.id));
        assertEquals("manual", Files.readString(f.file));
        Files.writeString(f.file, "second");
        restore(f, 1, "both").andExpect(jsonPath("$.applied").value(true));
        assertEquals("before", Files.readString(f.file));
        assertEquals(0, sessions.eventCount(f.id));
        assertTrue(conversations.load(f.id).isEmpty());
        assertEquals(com.pilot.core.session.SessionStatus.CREATED, sessions.find(f.id).orElseThrow().status());
    }

    @Test
    void forkPreservesOriginalHistoryAndFilesAndCanBranchAgain() throws Exception {
        Fixture f = fixture();
        String result = mvc.perform(post("/api/sessions/{id}/checkpoints/fork", f.id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("sequence", f.secondSequence))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String fork = mapper.readTree(result).path("sessionId").asString();
        assertNotEquals(f.id, fork);
        assertEquals(f.firstMemory, conversations.load(fork));
        assertEquals(3, sessions.eventCount(fork));
        assertEquals(6, sessions.eventCount(f.id));
        assertEquals("second", Files.readString(f.file));
        assertEquals(workspace.toString(), sessions.find(fork).orElseThrow().workspace());
        mvc.perform(get("/api/sessions/{id}/checkpoints", fork).param("sequence", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.conversationAvailable").value(true));
    }

    @Test
    void rejectsActiveRunsStalePreviewsInvalidTargetsAndUnavailableCheckpoints() throws Exception {
        Fixture f = fixture();
        String slot = runs.claimRun(f.id).orElseThrow();
        try { restore(f, 1, "both").andExpect(status().isConflict()); }
        finally { runs.abandon(slot); }
        mvc.perform(post("/api/sessions/{id}/checkpoints/restore", f.id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sequence\":1,\"mode\":\"both\",\"revision\":2}"))
                .andExpect(status().isConflict());
        restore(f, 2, "both").andExpect(status().isConflict());
        mvc.perform(post("/api/sessions/{id}/checkpoints/restore", f.id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sequence\":1}"))
                .andExpect(status().isBadRequest());
        var old = sessions.publish(f.id, "old_" + UUID.randomUUID(), new RunStarted("历史消息"));
        restore(f, old.sequence(), "both").andExpect(status().isConflict());
        assertEquals("second", Files.readString(f.file));
        assertTrue(runs.activeRun(f.id).isEmpty());
    }

    @Test
    void codeRestoreIsBlockedWhileAnotherSessionUsesWorkspace() throws Exception {
        Fixture f = fixture();
        String other = sessions.createSession("另一个对话", workspace.toString()).id();
        String slot = runs.claimRun(other).orElseThrow();
        try { restore(f, 1, "code").andExpect(status().isConflict()); }
        finally { runs.abandon(slot); }
        assertEquals("second", Files.readString(f.file));
    }

    private org.springframework.test.web.servlet.ResultActions restore(Fixture f, long sequence, String mode) throws Exception {
        return mvc.perform(post("/api/sessions/{id}/checkpoints/restore", f.id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("sequence", sequence, "mode", mode))));
    }

    private Fixture fixture() throws Exception {
        String id = sessions.createSession("检查点测试", workspace.toString()).id();
        Path file = workspace.resolve("a.txt");
        Files.writeString(file, "before");
        List<Message> first = List.of(Message.user("重复"), Message.assistant("第一轮回复", List.of()));
        run(id, file, List.of(), "first");
        plans.save(id, List.of(new PlanItem("原计划", PlanItem.COMPLETED)));
        long sequence = run(id, file, first, "second");
        conversations.save(id, List.of(Message.system("已压缩的最新记忆")));
        plans.save(id, List.of(new PlanItem("后续计划", PlanItem.PENDING)));
        return new Fixture(id, file, sequence, first);
    }

    private long run(String id, Path file, List<Message> memory, String result) throws Exception {
        String runId = "run_" + UUID.randomUUID();
        long sequence = sessions.publish(id, runId, new RunStarted("重复")).sequence();
        snapshots.saveConversation(runId, memory, plans.load(id));
        snapshots.bind(runId, workspace);
        snapshots.beforeWrite(file);
        Files.writeString(file, result);
        snapshots.finish();
        snapshots.clear();
        sessions.publish(id, runId, new AssistantDelta("回复"));
        sessions.publish(id, runId, new RunCompleted("完成", List.of("a.txt"), List.of()));
        return sequence;
    }

    private record Fixture(String id, Path file, long secondSequence, List<Message> firstMemory) { }
}
