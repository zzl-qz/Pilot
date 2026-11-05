package com.pilot.server.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import com.pilot.core.event.AssistantDelta;
import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.RunStarted;
import com.pilot.core.loop.Message;
import com.pilot.core.project.ProjectRepository;
import com.pilot.core.session.ConversationRepository;
import com.pilot.core.session.Run;
import com.pilot.core.session.SessionService;
import com.pilot.server.session.RunStarter;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "pilot.store=memory")
@AutoConfigureMockMvc
class SessionApiTest {
    private static final Path ATTACHMENT_ROOT =
        Path.of(System.getProperty("java.io.tmpdir"), "pilot-session-api-" + UUID.randomUUID());

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        TestRunStarter testRunStarter() {
            return new TestRunStarter();
        }
    }

    static class TestRunStarter implements RunStarter {
        private final AtomicBoolean running = new AtomicBoolean();
        private final List<String> queue = new ArrayList<>();

        void setRunning(boolean value) {
            running.set(value);
            queue.clear();
        }

        @Override
        public void start(String sessionId, String runId, String input) {
        }

        @Override
        public Optional<String> activeRun(String sessionId) {
            return running.get() ? Optional.of("run_active") : Optional.empty();
        }

        @Override
        public boolean steer(String sessionId, String text) {
            if (!running.get() || text == null || text.isBlank()) {
                return false;
            }
            queue.add(text.strip());
            return true;
        }

        @Override
        public boolean steerQueued(String sessionId, String runId, int index, String revision) {
            if (!activeRun(sessionId).filter(runId::equals).isPresent()
                || !RunStarter.queueRevision(queuedMessages(sessionId)).equals(revision)) {
                return false;
            }
            return removeQueued(sessionId, index);
        }

        @Override
        public List<String> queued(String sessionId) {
            return List.copyOf(queue);
        }

        @Override
        public ContextStatus contextStatus(String sessionId) {
            return new ContextStatus(18, 42_000, 120_000, true, true);
        }

        @Override
        public CompactionResult compactContext(String sessionId) {
            return new CompactionResult(true, 12, 42_000, 16_000, "已压缩 12 条较早消息");
        }

        @Override
        public boolean editQueued(String sessionId, int index, String text) {
            if (text == null || text.isBlank() || index < 0 || index >= queue.size()) {
                return false;
            }
            queue.set(index, text.strip());
            return true;
        }

        @Override
        public boolean moveQueued(String sessionId, int from, int to) {
            if (from < 0 || from >= queue.size() || to < 0 || to >= queue.size() || from == to) {
                return false;
            }
            queue.add(to, queue.remove(from));
            return true;
        }

        @Override
        public boolean removeQueued(String sessionId, int index) {
            if (index < 0 || index >= queue.size()) {
                return false;
            }
            queue.remove(index);
            return true;
        }
    }

    @DynamicPropertySource
    static void attachmentProperties(DynamicPropertyRegistry registry) {
        registry.add("pilot.attachments.dir", () -> ATTACHMENT_ROOT.toString());
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private SessionService sessions;

    @Autowired
    private TestRunStarter runStarter;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ConversationRepository conversations;

    @TempDir
    Path workspace;

    @Test
    void createSessionAndSendMessageReturnIds() throws Exception {
        String sessionId = createSession();

        mvc.perform(post("/api/sessions/{id}/messages", sessionId).contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"修复边界条件\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionId").value(sessionId)).andExpect(jsonPath("$.runId").isNotEmpty());

        List<EventEnvelope> events = sessions.events(sessionId);
        assertEquals(1, events.size());
        assertEquals("run.started", events.get(0).type());
        assertEquals("修复边界条件", ((RunStarted)events.get(0).payload()).input());
    }

    /** 刷新页面后前端靠这个接口恢复历史会话，不能只返回当前进程内新建的会话。 */
    @Test
    void listsSessionsForHistoryRestore() throws Exception {
        String sessionId = createSession();

        mvc.perform(get("/api/sessions")).andExpect(status().isOk()).andExpect(jsonPath("$[0].sessionId").isNotEmpty())
            .andExpect(jsonPath("$[0].status").isNotEmpty());

        String body = mvc.perform(get("/api/sessions")).andReturn().getResponse().getContentAsString();
        assertTrue(body.contains(sessionId), body);
    }

    /**
     * 侧边对话：从主对话分叉、继承模型记忆、不进主列表；不能在侧边里再开侧边；删掉即释放。
     */
    @Test
    void sideChatForksParentButStaysOutOfTheMainList() throws Exception {
        String parentId = createSession();
        conversations.save(parentId, List.of(Message.user("主线的上下文"), new Message("assistant", "看过了，没问题")));

        String body = mvc.perform(post("/api/sessions/{id}/side", parentId)).andExpect(status().isOk())
            .andExpect(jsonPath("$.parentSessionId").value(parentId)).andReturn().getResponse().getContentAsString();
        String sideId = json.readTree(body).get("sessionId").asText();
        assertFalse(sideId.equals(parentId), "侧边对话应该是独立的会话");

        // 继承模型记忆：侧边问「这里为什么」时能读到主线做过什么
        assertEquals(List.of("主线的上下文", "看过了，没问题"), conversations.load(sideId).stream().map(Message::content).toList());

        // 主列表只留给真正的任务，侧边对话不出现
        String list = mvc.perform(get("/api/sessions")).andReturn().getResponse().getContentAsString();
        assertTrue(list.contains(parentId), list);
        assertFalse(list.contains(sideId), list);

        // 侧边对话里不能再开侧边对话
        mvc.perform(post("/api/sessions/{id}/side", sideId)).andExpect(status().isConflict());

        // 关掉面板即丢弃：会话与记忆一起清掉
        mvc.perform(delete("/api/sessions/{id}", sideId)).andExpect(status().isNoContent());
        mvc.perform(get("/api/sessions/{id}", sideId)).andExpect(status().isNotFound());
        assertTrue(conversations.load(sideId).isEmpty(), "删除后不应再留下侧边对话的记忆");
    }

    @Test
    void deletingParentAlsoDeletesSideChatsUnknownToTheBrowser() throws Exception {
        String parent = createSession();
        String body = mvc.perform(post("/api/sessions/{id}/side", parent)).andExpect(status().isOk()).andReturn()
            .getResponse().getContentAsString();
        String side = json.readTree(body).get("sessionId").asText();
        conversations.save(side, List.of(Message.user("独立侧边记忆")));
        mvc.perform(delete("/api/sessions/{id}", parent)).andExpect(status().isNoContent());
        mvc.perform(get("/api/sessions/{id}", side)).andExpect(status().isNotFound());
        assertTrue(conversations.load(side).isEmpty());
    }

    @Test
    void exposesContextUsageAndManualCompaction() throws Exception {
        String sessionId = createSession();

        mvc.perform(get("/api/sessions/{id}/context", sessionId)).andExpect(status().isOk())
            .andExpect(jsonPath("$.usedTokens").value(42_000)).andExpect(jsonPath("$.limitTokens").value(120_000))
            .andExpect(jsonPath("$.percent").value(35)).andExpect(jsonPath("$.canCompact").value(true));

        mvc.perform(post("/api/sessions/{id}/context/compact", sessionId)).andExpect(status().isOk())
            .andExpect(jsonPath("$.compacted").value(true)).andExpect(jsonPath("$.tokensAfter").value(16_000));
    }

    @Test
    void queuesAttachmentsDuringActiveRunButRejectsStandaloneAcceptance() throws Exception {
        String sessionId = createSession();
        Path attachmentRoot = ATTACHMENT_ROOT.resolve(sessionId);
        runStarter.running.set(true);
        try {
            String task = json.writeValueAsString(Map.of("content", "带验收条件的新任务", "task",
                Map.of("constraints", List.of("别动 pom"), "acceptance", List.of())));
            mvc.perform(
                post("/api/sessions/{id}/messages", sessionId).contentType(MediaType.APPLICATION_JSON).content(task))
                .andExpect(status().isConflict());
            assertFalse(Files.exists(attachmentRoot), "冲突响应前不应创建附件目录或文件");

            // 二进制文件落盘后以路径形式进入排队正文，不再因为带附件被拒绝
            String attachment = json.writeValueAsString(Map.of("content", "检查这个文件", "attachments", List
                .of(Map.of("name", "payload.bin", "mimeType", "application/octet-stream", "dataBase64", "AQIDBA=="))));
            mvc.perform(post("/api/sessions/{id}/messages", sessionId).contentType(MediaType.APPLICATION_JSON)
                .content(attachment)).andExpect(status().isAccepted());
            assertTrue(Files.isDirectory(attachmentRoot), "排队消息的附件应该已经落盘");
            mvc.perform(get("/api/sessions/{id}/queue", sessionId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].content").value(Matchers.containsString("payload.bin")))
                .andExpect(jsonPath("$.entries[0].attachments.length()").value(0));

            mvc.perform(post("/api/sessions/{id}/messages", sessionId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"补充说明\"}")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sessionId").value(sessionId)).andExpect(jsonPath("$.runId").value("run_active"));
        } finally {
            runStarter.running.set(false);
        }
    }

    /** 历史记录和队列里的缩略图靠这个接口取回；只放行图片，其余落盘文件不外露。 */
    @Test
    void servesStoredImagesOnlyForTheOwningSession() throws Exception {
        String sessionId = createSession();
        byte[] png = {(byte)0x89, 'P', 'N', 'G', 1, 2, 3};
        String body = json.writeValueAsString(Map.of("content", "看图", "attachments",
            List.of(
                Map.of("name", "shot.png", "mimeType", "image/png", "dataBase64",
                    Base64.getEncoder().encodeToString(png)),
                Map.of("name", "payload.bin", "mimeType", "application/octet-stream", "dataBase64", "AQIDBA=="))));
        mvc.perform(
            post("/api/sessions/{id}/messages", sessionId).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());

        Path dir = ATTACHMENT_ROOT.resolve(sessionId);
        String image;
        String binary;
        try (var files = Files.list(dir)) {
            List<String> names = files.map(path -> path.getFileName().toString()).toList();
            image = names.stream().filter(name -> name.endsWith("-shot.png")).findFirst().orElseThrow();
            binary = names.stream().filter(name -> name.endsWith("-payload.bin")).findFirst().orElseThrow();
        }

        byte[] served = mvc.perform(get("/api/sessions/{id}/attachments/{file}", sessionId, image))
            .andExpect(status().isOk()).andExpect(MockMvcResultMatchers.content().contentType(MediaType.IMAGE_PNG))
            .andReturn().getResponse().getContentAsByteArray();
        assertEquals(png.length, served.length);

        mvc.perform(get("/api/sessions/{id}/attachments/{file}", sessionId, binary)).andExpect(status().isNotFound());
        mvc.perform(get("/api/sessions/{id}/attachments/{file}", sessionId, "../../etc-passwd"))
            .andExpect(status().isNotFound());
        String other = createSession();
        mvc.perform(get("/api/sessions/{id}/attachments/{file}", other, image)).andExpect(status().isNotFound());
    }

    /** 首屏只回放尾部一页，向上翻页用 before 游标。 */
    @Test
    void pagesHistoryFromTheTail() throws Exception {
        String sessionId = createSession();
        for (int i = 0; i < 5; i++) {
            sessions.publish(sessionId, "run_hist", new AssistantDelta("片段" + i));
        }

        mvc.perform(get("/api/sessions/{id}/history", sessionId).param("limit", "2")).andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(5)).andExpect(jsonPath("$.hasMore").value(true))
            .andExpect(jsonPath("$.events.length()").value(2)).andExpect(jsonPath("$.events[1].sequence").value(5));

        mvc.perform(get("/api/sessions/{id}/history", sessionId).param("limit", "2").param("before", "4"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.events[0].sequence").value(2))
            .andExpect(jsonPath("$.events[1].sequence").value(3)).andExpect(jsonPath("$.hasMore").value(true));

        mvc.perform(get("/api/sessions/{id}/history", sessionId).param("limit", "2").param("after", "2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.events[0].sequence").value(3))
            .andExpect(jsonPath("$.events[1].sequence").value(4)).andExpect(jsonPath("$.hasMore").value(true));

        mvc.perform(get("/api/sessions/{id}/history", sessionId).param("before", "4").param("after", "2"))
            .andExpect(status().isBadRequest());
    }

    /** 同一项目下不允许出现第二条空白对话；要求确认后可以「归档旧的再新建」。 */
    @Test
    void allowsOnlyOneBlankConversationPerProject() throws Exception {
        Path folder = Files.createDirectories(ATTACHMENT_ROOT.resolve("prj-" + UUID.randomUUID()));
        String projectId = "prj_" + UUID.randomUUID();
        projectRepository.create(projectId, "测试项目", List.of(folder.toString()), Instant.now());

        String first = mvc
            .perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("title", "新对话", "projectId", projectId))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.projectId").value(projectId))
            .andExpect(jsonPath("$.isBlank").value(true)).andReturn().getResponse().getContentAsString();
        String firstId = json.readTree(first).get("sessionId").asString();

        // 第二条：没有确认归档 → 409，界面上据此弹窗
        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("title", "新对话", "projectId", projectId))))
            .andExpect(status().isConflict());

        // 确认归档旧的再建：成功，且旧的那条变成 archived
        String second = mvc
            .perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("title", "新对话", "projectId", projectId, "replaceBlank", true))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String secondId = json.readTree(second).get("sessionId").asString();

        assertFalse(secondId.equals(firstId), "应该创建一条新的空白对话");
        assertTrue(sessions.find(firstId).orElseThrow().archived(), "旧的空白对话应被归档");
        assertEquals(1, sessions.listByProject(projectId).stream()
            .filter(session -> session.isBlank() && !session.archived()).count());
    }

    /** 发过第一条消息就不再是空白对话，否则它会被后来的「新建」当成可归档的空壳。 */
    @Test
    void blankFlagClearsAfterTheFirstMessage() throws Exception {
        Path folder = Files.createDirectories(ATTACHMENT_ROOT.resolve("prj-" + UUID.randomUUID()));
        String projectId = "prj_" + UUID.randomUUID();
        projectRepository.create(projectId, "测试项目", List.of(folder.toString()), Instant.now());

        String body = mvc
            .perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("projectId", projectId))))
            .andReturn().getResponse().getContentAsString();
        String sessionId = json.readTree(body).get("sessionId").asString();
        assertTrue(sessions.find(sessionId).orElseThrow().isBlank());

        mvc.perform(post("/api/sessions/{id}/messages", sessionId).contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"你好\"}")).andExpect(status().isOk());

        assertFalse(sessions.find(sessionId).orElseThrow().isBlank(), "发过消息就不是空白对话了");
        // 空白位释放之后，可以再新建一条
        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("projectId", projectId)))).andExpect(status().isOk());
    }

    /** 归档的对话不进主列表的「活跃」集，但接口还能读到；取消归档可以恢复。 */
    @Test
    void archivesAndRestoresAConversation() throws Exception {
        String sessionId = createSession();

        mvc.perform(MockMvcRequestBuilders.patch("/api/sessions/{id}/archive", sessionId)
            .contentType(MediaType.APPLICATION_JSON).content("{\"archived\":true}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.archived").value(true));

        mvc.perform(MockMvcRequestBuilders.patch("/api/sessions/{id}/archive", sessionId)
            .contentType(MediaType.APPLICATION_JSON).content("{\"archived\":false}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.archived").value(false));
    }

    /** 项目下的对话只能在项目绑定的文件夹之间切换。 */
    @Test
    void onlySwitchesBetweenFoldersBoundToTheProject() throws Exception {
        Path allowed = Files.createDirectories(ATTACHMENT_ROOT.resolve("prj-a-" + UUID.randomUUID()));
        Path outside = Files.createDirectories(ATTACHMENT_ROOT.resolve("outside-" + UUID.randomUUID()));
        String projectId = "prj_" + UUID.randomUUID();
        projectRepository.create(projectId, "测试项目", List.of(allowed.toString()), Instant.now());

        String body = mvc
            .perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("projectId", projectId))))
            .andReturn().getResponse().getContentAsString();
        String sessionId = json.readTree(body).get("sessionId").asString();

        mvc.perform(MockMvcRequestBuilders.patch("/api/sessions/{id}/workspace", sessionId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("workspace", outside.toString()))))
            .andExpect(status().isBadRequest());

        mvc.perform(MockMvcRequestBuilders.patch("/api/sessions/{id}/workspace", sessionId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("workspace", allowed.toString())))).andExpect(status().isOk())
            .andExpect(jsonPath("$.workspace").value(allowed.toString()));
    }

    /** 权限档位要能在运行时切换，改完立刻反映到 health，不需要重启。 */
    @Test
    void switchesPermissionModeAtRuntime() throws Exception {
        try {
            mvc.perform(put("/api/settings/permission-mode").contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"AUTO_EDIT\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.permissionMode").value("AUTO_EDIT"));

            mvc.perform(get("/api/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.permissionMode").value("AUTO_EDIT"));

            mvc.perform(get("/api/settings")).andExpect(status().isOk())
                .andExpect(jsonPath("$.permissionMode").value("AUTO_EDIT"))
                .andExpect(jsonPath("$.permissionModes").isArray());
        } finally {
            mvc.perform(put("/api/settings/permission-mode").contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"READ_ONLY\"}")).andExpect(status().isOk());
        }
    }

    @Test
    void rejectsUnknownPermissionMode() throws Exception {
        mvc.perform(
            put("/api/settings/permission-mode").contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"ROOT\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createsSessionWithSelectedWorkspace() throws Exception {
        String body = mvc
            .perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("title", "选目录", "workspace", workspace.toString()))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.workspace").isNotEmpty()).andReturn().getResponse()
            .getContentAsString();

        String sessionId = json.readTree(body).get("sessionId").asText();
        assertEquals(workspace.toRealPath().toString(), json.readTree(body).get("workspace").asText());
        assertEquals(workspace.toRealPath().toString(), sessions.find(sessionId).orElseThrow().workspace());
    }

    @Test
    void rejectsMissingWorkspaceDirectory() throws Exception {
        String missing = workspace.resolve("does-not-exist").toString();
        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("title", "坏目录", "workspace", missing))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void browsesLocalDirectories() throws Exception {
        Files.createDirectory(workspace.resolve("child-project"));

        mvc.perform(get("/api/workspaces/browse").param("path", workspace.toString())).andExpect(status().isOk())
            .andExpect(jsonPath("$.directories[0].name").value("child-project"))
            .andExpect(jsonPath("$.gitRepository").value(false));
    }

    @Test
    void rejectsRollbackWithoutSnapshot() throws Exception {
        mvc.perform(post("/api/runs/{runId}/rollback", "run_missing")).andExpect(status().isNotFound());
    }

    @Test
    void rejectsMessageForUnknownSession() throws Exception {
        mvc.perform(post("/api/sessions/{id}/messages", "ses_missing").contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"hi\"}")).andExpect(status().isNotFound());
    }

    @Test
    void streamsStoredEventsAsSse() throws Exception {
        String sessionId = createSession();
        Run run = sessions.sendMessage(sessionId, "你好");
        sessions.publish(sessionId, run.runId(), new AssistantDelta("世界"));

        MvcResult result =
            mvc.perform(get("/api/sessions/{id}/events", sessionId)).andExpect(request().asyncStarted()).andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("event:run.started"), body);
        assertTrue(body.contains("event:assistant.delta"), body);
        assertTrue(body.contains("id:1"), body);
        assertTrue(body.contains("\"sequence\":2"), body);
    }

    @Test
    void replaysOnlyEventsAfterCursor() throws Exception {
        String sessionId = createSession();
        Run run = sessions.sendMessage(sessionId, "你好");
        sessions.publish(sessionId, run.runId(), new AssistantDelta("世界"));

        MvcResult result = mvc.perform(get("/api/sessions/{id}/events", sessionId).param("after", "1"))
            .andExpect(request().asyncStarted()).andReturn();

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains("event:run.started"), body);
        assertTrue(body.contains("event:assistant.delta"), body);
    }

    /** 计划清单刷新后要能恢复，空计划返回空数组而不是 404。 */
    @Test
    void returnsEmptyPlanForFreshSession() throws Exception {
        String sessionId = createSession();

        mvc.perform(get("/api/sessions/{id}/plan", sessionId)).andExpect(status().isOk())
            .andExpect(jsonPath("$.plan").isArray()).andExpect(jsonPath("$.plan.length()").value(0));
    }

    /** 改已经发出去的一句：这句和后面的事件都删掉，再用新内容开一轮。 */
    @Test
    void resendsFromEditedUserMessage() throws Exception {
        runStarter.setRunning(false);
        String sessionId = createSession();
        mvc.perform(post("/api/sessions/{id}/messages", sessionId).contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"第一句\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/sessions/{id}/messages", sessionId).contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"第二句\"}")).andExpect(status().isOk());

        mvc.perform(post("/api/sessions/{id}/resend", sessionId).contentType(MediaType.APPLICATION_JSON)
            .content("{\"sequence\":1,\"content\":\"改过的第一句\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.runId").isNotEmpty());

        List<EventEnvelope> events = sessions.events(sessionId);
        assertEquals(1, events.size());
        assertEquals("改过的第一句", ((RunStarted)events.get(0).payload()).input());
    }

    @Test
    void rejectsResendWhileRunning() throws Exception {
        String sessionId = createSession();
        mvc.perform(post("/api/sessions/{id}/messages", sessionId).contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"第一句\"}")).andExpect(status().isOk());
        runStarter.setRunning(true);
        try {
            mvc.perform(post("/api/sessions/{id}/resend", sessionId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sequence\":1,\"content\":\"先别改\"}")).andExpect(status().isConflict());
            assertEquals(1, sessions.events(sessionId).size());
        } finally {
            runStarter.setRunning(false);
        }
    }

    @Test
    void guidesQueuedMessageWithoutRestartAndRejectsStaleQueueOrRun() throws Exception {
        String id = createSession();
        runStarter.setRunning(true);
        try {
            runStarter.followup(id, "引导当前任务");
            runStarter.followup(id, "稍后再做");
            String revision = RunStarter.queueRevision(runStarter.queuedMessages(id));
            mvc.perform(
                post("/api/sessions/{id}/queue/0/steer", id).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
            mvc.perform(post("/api/sessions/{id}/queue/0/steer", id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("runId", "old", "revision", revision))))
                .andExpect(status().isConflict());
            mvc.perform(post("/api/sessions/{id}/queue/0/steer", id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("runId", "run_active", "revision", revision))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0]").value("稍后再做"));
            mvc.perform(post("/api/sessions/{id}/queue/0/steer", id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("runId", "run_active", "revision", revision))))
                .andExpect(status().isConflict());
            assertEquals(List.of("稍后再做"), runStarter.queued(id));
        } finally {
            runStarter.setRunning(false);
        }
    }

    /** 运行中再发的话进队列，发出去之前可以改、可以删。 */
    @Test
    void queuesFollowUpUntilItIsEditedOrRemoved() throws Exception {
        String sessionId = createSession();
        runStarter.setRunning(true);
        try {
            mvc.perform(post("/api/sessions/{id}/messages", sessionId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"等会儿再做这个\"}")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value("run_active"));

            mvc.perform(get("/api/sessions/{id}/queue", sessionId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0]").value("等会儿再做这个"));

            mvc.perform(put("/api/sessions/{id}/queue/{index}", sessionId, 0).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"改成先列计划\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0]").value("改成先列计划"));

            mvc.perform(post("/api/sessions/{id}/messages", sessionId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"第二件事\"}")).andExpect(status().isAccepted());

            mvc.perform(post("/api/sessions/{id}/queue/{index}/move", sessionId, 0).param("to", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0]").value("第二件事"))
                .andExpect(jsonPath("$.items[1]").value("改成先列计划"));
            mvc.perform(post("/api/sessions/{id}/queue/{index}/move", sessionId, 0).param("to", "5"))
                .andExpect(status().isNotFound());

            mvc.perform(delete("/api/sessions/{id}/queue/{index}", sessionId, 0)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
            mvc.perform(delete("/api/sessions/{id}/queue/{index}", sessionId, 0)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        } finally {
            runStarter.setRunning(false);
        }
    }

    /** 没有对话记忆时不能续跑，要明确返回冲突而不是静默开一个空 run。 */
    @Test
    void rejectsContinueWithoutConversation() throws Exception {
        String sessionId = createSession();

        mvc.perform(post("/api/sessions/{id}/continue", sessionId)).andExpect(status().isConflict());
    }

    @Test
    void sessionOrganizationPersistsAndRejectsInvalidEdits() throws Exception {
        runStarter.setRunning(false);
        String body = mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(body).get("sessionId").asText();
        mvc.perform(post("/api/sessions/{id}/messages", id).contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"## 完善会话列表\\n检查边界\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/sessions/{id}", id)).andExpect(jsonPath("$.title").value("完善会话列表"))
            .andExpect(jsonPath("$.titleManual").value(false)).andExpect(jsonPath("$.updatedAt").isNotEmpty());
        mvc.perform(MockMvcRequestBuilders.patch("/api/sessions/{id}/preferences", id)
            .contentType(MediaType.APPLICATION_JSON).content("{\"pinned\":true,\"unread\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.pinned").value(true))
            .andExpect(jsonPath("$.unread").value(true));
        mvc.perform(MockMvcRequestBuilders.patch("/api/sessions/{id}/preferences", id)
            .contentType(MediaType.APPLICATION_JSON).content("{\"unread\":false}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.pinned").value(true)).andExpect(jsonPath("$.unread").value(false));
        mvc.perform(MockMvcRequestBuilders.patch("/api/sessions/{id}/preferences", id)
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(MockMvcRequestBuilders.patch("/api/sessions/{id}", id).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"手动名称\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.titleManual").value(true));
        for (String title : List.of("   ", "字".repeat(121))) {
            mvc.perform(MockMvcRequestBuilders.patch("/api/sessions/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("title", title)))).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/sessions/{id}", id)).andExpect(jsonPath("$.title").value("手动名称"));
        runStarter.setRunning(true);
        try {
            Instant before = sessions.find(id).orElseThrow().updatedAt();
            mvc.perform(post("/api/sessions/{id}/messages", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"排队的后续消息\"}")).andExpect(status().isAccepted());
            assertTrue(sessions.find(id).orElseThrow().updatedAt().isAfter(before));
        } finally {
            runStarter.setRunning(false);
        }
    }

    private String createSession() throws Exception {
        String body =
            mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"测试会话\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessionId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("CREATED")).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("sessionId").asText();
    }
}
