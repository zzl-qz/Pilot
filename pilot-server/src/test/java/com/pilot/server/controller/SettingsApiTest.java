package com.pilot.server.controller;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 模型设置的对外契约：留空保持、明确清除、坏端点不落盘、可用模型探测只读。 */
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
    void runtimePricingAndCapabilitiesRoundTripThroughJsonApi() throws Exception {
        updateModel("{\"endpoint\":\"https://runtime.test/v1\",\"model\":\"runtime-model\"}");
        mvc.perform(put("/api/settings/model/runtime").contentType(MediaType.APPLICATION_JSON).content("""
                {"model":"runtime-model","endpoint":"https://runtime.test/v1","options":{
                  "provider":"generic","cacheMode":"auto","compactionMode":"short",
                  "pricing":{"currency":"USD","input":1.5,"cachedInput":0,"cacheWrite":1.5,"output":2,"source":"测试单价"}}}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.options.pricing.cachedInput").value(0))
                .andExpect(jsonPath("$.effective.provider").value("generic"))
                .andExpect(jsonPath("$.effective.instructionMessages").value(false));
        mvc.perform(get("/api/settings/model/runtime"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.options.pricing.input").value(1.5));
        mvc.perform(put("/api/settings/model/runtime").contentType(MediaType.APPLICATION_JSON).content("""
                {"model":"runtime-model","endpoint":"https://runtime.test/v1","options":{"provider":"generic","cacheMode":"explicit"}}
                """))
                .andExpect(status().isBadRequest());
        updateModel("{\"model\":\"runtime-other\"}");
        mvc.perform(put("/api/settings/model/runtime").contentType(MediaType.APPLICATION_JSON).content("""
                {"model":"runtime-model","endpoint":"https://runtime.test/v1","options":{"provider":"auto","cacheMode":"auto"}}
                """))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/settings/model/runtime")).andExpect(status().isOk())
                .andExpect(jsonPath("$.options.pricing.input").doesNotExist());
    }

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

    /** 探测入参带完整 chat/completions 地址也能换算成 /v1/models，结果只回模型名。 */
    @Test
    void listsModelsFromExplicitEndpoint() throws Exception {
        HttpServer server = modelsServer(200, "{\"data\":[{\"id\":\"gpt-z\"},{\"id\":\"gpt-a\"}]}");
        server.start();
        try {
            listModels("{\"endpoint\":\"http://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions\",\"apiKey\":\"sk-test\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.models.length()").value(2))
                    .andExpect(jsonPath("$.models[0]").value("gpt-a"))
                    .andExpect(jsonPath("$.models[1]").value("gpt-z"));
        } finally {
            server.stop(0);
        }
    }

    /** 端点与密钥留空时回退到已保存值：改完配置后无需重填就能探测。 */
    @Test
    void fallsBackToSavedEndpointAndKeyWhenProbeFieldsBlank() throws Exception {
        HttpServer server = modelsServer(200, "{\"data\":[{\"id\":\"saved-model\"}]}");
        server.start();
        try {
            updateModel("{\"endpoint\":\"http://127.0.0.1:" + server.getAddress().getPort()
                    + "\",\"model\":\"gpt-a\",\"apiKey\":\"sk-saved\"}");
            listModels("{}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.models[0]").value("saved-model"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsNonHttpProbeEndpointWithoutTouchingSavedConfig() throws Exception {
        updateModel("{\"endpoint\":\"https://good.example/v1\",\"model\":\"gpt-safe\",\"apiKey\":\"sk-safe\"}")
                .andExpect(jsonPath("$.modelApiKeySet").value(true));

        mvc.perform(post("/api/settings/model/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"file:/etc/passwd\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/settings"))
                .andExpect(jsonPath("$.modelEndpoint").value("https://good.example/v1"));
    }

    /** 上游失败映射 502；MockMvc 不走 /error 转发，这里只校验状态码。 */
    @Test
    void mapsUpstreamFailureToBadGateway() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        try {
            listModels("{\"endpoint\":\"http://127.0.0.1:" + server.getAddress().getPort() + "/v1\"}")
                    .andExpect(status().isBadGateway());
        } finally {
            server.stop(0);
        }
    }

    private ResultActions listModels(String body) throws Exception {
        return mvc.perform(post("/api/settings/model/models")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static HttpServer modelsServer(int status, String responseBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        return server;
    }
}
