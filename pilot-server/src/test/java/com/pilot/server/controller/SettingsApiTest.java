package com.pilot.server.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 模型设置的对外契约：留空保持、明确清除、坏端点不落盘。 */
@SpringBootTest(properties = "pilot.store=memory")
@AutoConfigureMockMvc
class SettingsApiTest {
    private static final Path DATA = Path.of(System.getProperty("java.io.tmpdir"), "pilot-settings-api-test");

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("pilot.data.dir", DATA::toString);
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void blankApiKeyKeepsTheSavedOne() throws Exception {
        updateModel("{\"endpoint\":\"https://gateway.test/v1\",\"model\":\"gpt-a\",\"apiKey\":\"sk-a\"}")
                .andExpect(jsonPath("$.model").value("gpt-a"))
                .andExpect(jsonPath("$.modelApiKeySet").value(true));

        updateModel("{\"model\":\"gpt-b\"}")
                .andExpect(jsonPath("$.model").value("gpt-b"))
                .andExpect(jsonPath("$.modelApiKeySet").value(true))
                .andExpect(jsonPath("$.modelApiKeyMasked").isNotEmpty())
                .andExpect(jsonPath("$.modelEndpoint").value("https://gateway.test/v1"));
    }

    @Test
    void clearsApiKeyExplicitlyAndIdempotently() throws Exception {
        updateModel("{\"endpoint\":\"https://gateway.test/v1\",\"model\":\"gpt-a\",\"apiKey\":\"sk-a\"}")
                .andExpect(jsonPath("$.modelApiKeySet").value(true));

        mvc.perform(delete("/api/settings/model/api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelApiKeySet").value(false))
                .andExpect(jsonPath("$.modelApiKeyMasked").value(""));

        mvc.perform(delete("/api/settings/model/api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelApiKeySet").value(false));

        mvc.perform(get("/api/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelApiKeySet").value(false))
                .andExpect(jsonPath("$.model").value("gpt-a"));
    }

    /** 清除标记与 PUT 同一个入口，便于界面一次提交模型、端点与密钥状态。 */
    @Test
    void clearFlagInUpdatePayloadWorksToo() throws Exception {
        updateModel("{\"endpoint\":\"https://gateway.test/v1\",\"model\":\"gpt-a\",\"apiKey\":\"sk-a\"}")
                .andExpect(jsonPath("$.modelApiKeySet").value(true));

        updateModel("{\"clearApiKey\":true}")
                .andExpect(jsonPath("$.modelApiKeySet").value(false));
    }

    @Test
    void rejectsBadEndpointWithoutSavingAnything() throws Exception {
        updateModel("{\"endpoint\":\"https://good.example/v1\",\"model\":\"gpt-safe\",\"apiKey\":\"sk-safe\"}")
                .andExpect(jsonPath("$.modelApiKeySet").value(true));

        // MockMvc 不走 /error 转发，这里只校验状态码与「什么都没写进去」
        mvc.perform(put("/api/settings/model")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"file:/etc/passwd\",\"model\":\"gpt-bad\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/settings"))
                .andExpect(jsonPath("$.modelEndpoint").value("https://good.example/v1"))
                .andExpect(jsonPath("$.model").value("gpt-safe"))
                .andExpect(jsonPath("$.modelApiKeySet").value(true));
    }

    private ResultActions updateModel(String body) throws Exception {
        return mvc.perform(put("/api/settings/model")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }
}
