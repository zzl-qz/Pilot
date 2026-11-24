package com.pilot.server.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 多模型预设接口测试：创建、继承密钥、脱敏、编辑、一键切换与删除。 */
@SpringBootTest(properties = {"pilot.store=memory"})
@AutoConfigureMockMvc
class ModelProfilesControllerTest {
    private static final Path DATA = Path.of(System.getProperty("java.io.tmpdir"), "pilot-model-profiles-test");

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("pilot.data.dir", DATA::toString);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void crudAndOneClickActivateNeverLeaksPlaintextApiKey() throws Exception {
        // 先设置当前模型带密钥，测试 inheritCurrentKey
        mvc.perform(put("/api/settings/model").contentType(MediaType.APPLICATION_JSON)
            .content("{\"endpoint\":\"https://cloud.example/v1\",\"model\":\"deepseek-chat\",\"apiKey\":\"sk-secret-value-9988\"}"))
            .andExpect(status().isOk());

        MvcResult createdCloud = mvc.perform(post("/api/settings/model/profiles").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"主力云端\",\"endpoint\":\"https://cloud.example/v1\",\"model\":\"deepseek-chat\",\"inheritCurrentKey\":true,\"contextTokens\":128000,\"maxOutputTokens\":8192}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.profiles[0].name").value("主力云端"))
            .andExpect(jsonPath("$.profiles[0].apiKeySet").value(true))
            .andReturn();
        String cloudBody = createdCloud.getResponse().getContentAsString();
        assertFalse(cloudBody.contains("sk-secret-value-9988"), "预设响应不得泄露明文 API Key");
        String cloudId = mapper.readTree(cloudBody).path("profiles").get(0).path("id").asText();

        // 创建免 Key 本地 Ollama 预设
        MvcResult createdLocal = mvc.perform(post("/api/settings/model/profiles").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"本地 Ollama\",\"endpoint\":\"http://127.0.0.1:11434/v1\",\"model\":\"qwen2.5-coder:14b\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.profiles.length()").value(2))
            .andReturn();
        JsonNode profilesNode = mapper.readTree(createdLocal.getResponse().getContentAsString()).path("profiles");
        String localId = profilesNode.get(1).path("id").asText();

        // 重名与非法端点应返回 400
        mvc.perform(post("/api/settings/model/profiles").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"本地 Ollama\",\"endpoint\":\"http://127.0.0.1:11434/v1\",\"model\":\"m\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/settings/model/profiles").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"坏地址\",\"endpoint\":\"ftp://bad.host\",\"model\":\"m\"}"))
            .andExpect(status().isBadRequest());

        // 一键切换到本地免 Key 预设：/api/settings 同步更新且清空旧 Key
        mvc.perform(post("/api/settings/model/profiles/" + localId + "/activate"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activeProfileId").value(localId));
        mvc.perform(get("/api/settings"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model").value("qwen2.5-coder:14b"))
            .andExpect(jsonPath("$.modelEndpoint").value("http://127.0.0.1:11434/v1"))
            .andExpect(jsonPath("$.modelApiKeySet").value(false));

        // 切回云端预设：保存的密钥和窗口完整恢复
        mvc.perform(post("/api/settings/model/profiles/" + cloudId + "/activate"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activeProfileId").value(cloudId));
        mvc.perform(get("/api/settings"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model").value("deepseek-chat"))
            .andExpect(jsonPath("$.modelApiKeySet").value(true))
            .andExpect(jsonPath("$.contextTokens").value(128000))
            .andExpect(jsonPath("$.maxOutputTokens").value(8192));

        // 编辑预设留空 apiKey 保留旧 Key；clearApiKey=true 才清空
        mvc.perform(put("/api/settings/model/profiles/" + cloudId).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"主力云端-改\",\"model\":\"deepseek-reasoner\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.profiles[0].name").value("主力云端-改"))
            .andExpect(jsonPath("$.profiles[0].apiKeySet").value(true));
        mvc.perform(put("/api/settings/model/profiles/" + cloudId).contentType(MediaType.APPLICATION_JSON)
            .content("{\"clearApiKey\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.profiles[0].apiKeySet").value(false));

        // 设置与清除自动故障转移备用预设，以及删除备用预设时自动清空引用
        mvc.perform(put("/api/settings/model/profiles/fallback").contentType(MediaType.APPLICATION_JSON)
            .content("{\"profileId\":\"" + localId + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fallbackProfileId").value(localId))
            .andExpect(jsonPath("$.profiles[1].fallback").value(true));
        mvc.perform(put("/api/settings/model/profiles/fallback").contentType(MediaType.APPLICATION_JSON)
            .content("{\"profileId\":\"prof_missing\"}"))
            .andExpect(status().isNotFound());

        // 删除预设 & 删除不存在预设返回 404（删除备用预设时自动清理 fallbackProfileId）
        mvc.perform(delete("/api/settings/model/profiles/" + localId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fallbackProfileId").value(""))
            .andExpect(jsonPath("$.profiles.length()").value(1));
        mvc.perform(delete("/api/settings/model/profiles/prof_nonexistent"))
            .andExpect(status().isNotFound());
    }
}
