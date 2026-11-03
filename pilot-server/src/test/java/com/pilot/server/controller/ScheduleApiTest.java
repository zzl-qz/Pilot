package com.pilot.server.controller;

import com.pilot.core.session.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"pilot.store=memory", "pilot.data.dir=${java.io.tmpdir}/pilot-schedule-api-${random.uuid}"})
@AutoConfigureMockMvc
class ScheduleApiTest {
    @Autowired MockMvc mvc;
    @Autowired SessionService sessions;
    @Autowired ObjectMapper mapper;
    @Test void createListPauseRescheduleCancelAndValidate() throws Exception {
        String source = sessions.createSession("定时测试").id();
        var response = mvc.perform(post("/api/schedules").contentType("application/json").content(mapper.writeValueAsString(Map.of(
                "sourceSessionId", source, "name", "定时检查", "content", "检查仓库", "runAt", Instant.now().plusSeconds(86400).toString(), "timezone", "Asia/Shanghai"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("SCHEDULED"))
                .andExpect(jsonPath("$.requirements.automation.requireResearch").value(true)).andReturn();
        String id = mapper.readTree(response.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(get("/api/schedules")).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(post("/api/schedules/"+id+"/control").contentType("application/json").content("{\"action\":\"pause\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PAUSED"));
        mvc.perform(post("/api/schedules/"+id+"/control").contentType("application/json").content("{\"action\":\"reschedule\",\"runAt\":\"2000-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/schedules/"+id+"/control").contentType("application/json").content("{\"action\":\"cancel\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CANCELLED"));
        mvc.perform(post("/api/schedules").contentType("application/json").content("{\"content\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
