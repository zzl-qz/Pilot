package com.pilot.server.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunStarted;
import com.pilot.core.session.SessionService;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    properties = {"pilot.store=memory", "pilot.data.dir=${java.io.tmpdir}/pilot-schedule-api-${random.uuid}"})
@AutoConfigureMockMvc
class ScheduleApiTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    SessionService sessions;
    @Autowired
    ObjectMapper mapper;

    @Test
    void createListPauseRescheduleCancelAndValidate() throws Exception {
        String source = sessions.createSession("定时测试").id();
        var response = mvc.perform(post("/api/schedules").contentType("application/json")
            .content(mapper.writeValueAsString(Map.of("sourceSessionId", source, "isolated", false, "name", "定时检查",
                "content", "检查仓库", "runAt", Instant.now().plusSeconds(86400).toString(), "timezone", "Asia/Shanghai"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("SCHEDULED"))
            .andExpect(jsonPath("$.requirements.automation.requireResearch").value(true)).andReturn();
        String id = mapper.readTree(response.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(get("/api/schedules")).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(
            post("/api/schedules/" + id + "/control").contentType("application/json").content("{\"action\":\"pause\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PAUSED"));
        mvc.perform(post("/api/schedules/" + id + "/control").contentType("application/json")
            .content("{\"action\":\"reschedule\",\"runAt\":\"2000-01-01T00:00:00Z\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/schedules/" + id + "/control").contentType("application/json")
            .content("{\"action\":\"cancel\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("CANCELLED"));
        mvc.perform(post("/api/schedules").contentType("application/json").content("{\"content\":\"\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void resultRoutesPersistReadStateAndRejectStaleActions() throws Exception {
        String source = sessions.createSession("收件测试").id();
        sessions.publish(source, "result_api_run", new RunCompleted("完成验证", List.of("a.java"), List.of("test")));
        var response = mvc.perform(get("/api/results/result_api_run")).andExpect(status().isOk())
            .andExpect(jsonPath("$.item.read").value(false)).andExpect(jsonPath("$.verification").value("UNKNOWN"))
            .andReturn();
        String revision =
            mapper.readTree(response.getResponse().getContentAsString()).get("item").get("revision").asText();
        mvc.perform(post("/api/results/result_api_run/receipt").contentType("application/json")
            .content(mapper.writeValueAsString(Map.of("revision", revision, "action", "read"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.read").value(true))
            .andExpect(jsonPath("$.handled").value(false));
        mvc.perform(post("/api/results/result_api_run/receipt").contentType("application/json")
            .content("{\"revision\":\"stale\",\"action\":\"handle\"}")).andExpect(status().isConflict());
        mvc.perform(get("/api/results/research/" + source)).andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void defaultIsolationRejectsNonGitDirectoryWithoutCreatingSession() throws Exception {
        var folder = Files.createTempDirectory("pilot-no-git-");
        try {
            String source = sessions.createSession("非 Git 目录", folder.toString()).id();
            int count = sessions.list().size();
            mvc.perform(post("/api/schedules").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("sourceSessionId", source, "content", "检查目录", "runAt",
                    Instant.now().plusSeconds(86400).toString()))))
                .andExpect(status().isBadRequest());
            Assertions.assertEquals(count, sessions.list().size());
        } finally {
            Files.delete(folder);
        }
    }

    @Test
    void recurringAndTemplateRoutesValidateAndControlWithoutLaunchingModels() throws Exception {
        String source = sessions.createSession("周期 API 测试").id();
        var task = Map.of("name", "每天维护", "sourceSessionId", source, "content", "检查既有链路", "runAt",
            Instant.now().plusSeconds(86400).toString(), "timezone", "Asia/Shanghai", "isolated", false);
        var response = mvc
            .perform(post("/api/automation/series").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("task", task, "cron", "0 0 9 * * *", "misfire", "ONCE"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ACTIVE")).andReturn();
        String id = mapper.readTree(response.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(post("/api/automation/series/" + id).contentType("application/json")
            .content("{\"version\":1,\"action\":\"pause\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("PAUSED"));
        mvc.perform(post("/api/automation/series/" + id).contentType("application/json")
            .content("{\"version\":1,\"action\":\"resume\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/automation/times").contentType("application/json").content(
            "{\"localTime\":\"2026-03-08T02:30\",\"timezone\":\"America/New_York\",\"cron\":\"0 30 2 * * *\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/automation/templates").contentType("application/json")
            .content(mapper.writeValueAsString(Map.of("version", 0, "name", "维护模板", "task", task))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.task.sourceSessionId").doesNotExist());
    }

    @Test
    void searchAndBookmarkRoutesUsePersistedEventIdentity() throws Exception {
        String source = sessions.createSession("检索 API 测试").id();
        sessions.publish(source, "search_api_run", new RunStarted("检索特有词和中文路径"));
        mvc.perform(get("/api/library/search").param("q", "检索特有词")).andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].sessionId").value(source));
        mvc.perform(put("/api/library/" + source + "/bookmarks/1")).andExpect(status().isOk())
            .andExpect(jsonPath("$.sequence").value(1));
        mvc.perform(get("/api/library/" + source + "/bookmarks")).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].sequence").value(1));
        mvc.perform(delete("/api/library/" + source + "/bookmarks/1")).andExpect(status().isOk());
        mvc.perform(put("/api/library/" + source + "/bookmarks/9999")).andExpect(status().isBadRequest());
    }

    @Test
    void notificationSettingsUseVersionChecksAndRejectInvalidEndpoints() throws Exception {
        var response = mvc.perform(get("/api/deliveries/settings")).andExpect(status().isOk()).andReturn();
        long version = mapper.readTree(response.getResponse().getContentAsString()).get("version").asLong();
        mvc.perform(put("/api/deliveries/settings").contentType("application/json")
            .content(mapper.writeValueAsString(Map.of("version", version, "desktop", true, "webhookUrl", ""))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.desktop").value(true));
        mvc.perform(put("/api/deliveries/settings").contentType("application/json")
            .content(mapper.writeValueAsString(Map.of("version", version, "desktop", false, "webhookUrl", ""))))
            .andExpect(status().isConflict());
        mvc.perform(put("/api/deliveries/settings").contentType("application/json")
            .content(mapper
                .writeValueAsString(Map.of("version", version + 1, "desktop", false, "webhookUrl", "file:///invalid"))))
            .andExpect(status().isBadRequest());
    }
}
