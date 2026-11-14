package com.pilot.server.model;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.pilot.core.event.ModelRequestCompleted;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.loop.Message;
import com.pilot.core.model.ModelClient;
import com.pilot.core.session.SessionService;
import com.pilot.server.session.RunStarter;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 使用真实配置代理和 HTTP 客户端，核对设置接口、任务请求、统计事件中的模型一致。 */
@SpringBootTest(properties = {"pilot.store=memory", "pilot.runtime.provision=false", "pilot.web-search.local=false",
    "pilot.agent.goal-mode-enabled=false", "pilot.agent.completion-checks=false"})
@AutoConfigureMockMvc
class ModelSelectionIntegrationTest {
    private static final Path DATA =
        Path.of(System.getProperty("java.io.tmpdir"), "pilot-model-selection-" + UUID.randomUUID());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("pilot.data.dir", DATA::toString);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    SessionService sessions;
    @Autowired
    RunStarter runs;
    @Autowired
    ModelClient client;
    @TempDir
    Path workspace;

    @Test
    void selectedModelReachesTheWireAndUsageEventsWithoutRestartingTheSession() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        List<String> authorization = new CopyOnWriteArrayList<>();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/chat/completions", exchange -> {
            JsonNode body = mapper.readTree(exchange.getRequestBody().readAllBytes());
            requests.add(body);
            authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            boolean streaming = body.path("stream").asBoolean(false);
            String response = streaming
                ? """
                    data: {"choices":[{"index":0,"delta":{"content":"已完成"},"finish_reason":null}]}

                    data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}

                    data: [DONE]

                    """
                : """
                    {"choices":[{"message":{"role":"assistant","content":"已完成"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":2}}
                    """;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        upstream.start();
        try {
            String endpoint = "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1";
            String session = sessions.createSession("模型同步验收", workspace.toString()).id();
            List<String> selected = List.of("model-a", "model-b", "provider/Custom-Model");
            for (int index = 0; index < selected.size(); index++) {
                String model = selected.get(index);
                Map<String, String> update =
                    index == 0 ? Map.of("model", model, "endpoint", endpoint, "apiKey", "fixture-key")
                        : Map.of("model", model, "endpoint", "", "apiKey", "");
                mvc.perform(put("/api/settings/model").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(update))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.model").value(model));
                mvc.perform(post("/api/sessions/{id}/messages", session).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"content\":\"请回复已完成\"}")).andExpect(status().isOk());
                long expected = index + 1;
                await().atMost(Duration.ofSeconds(15))
                    .until(() -> runs.activeRun(session).isEmpty() && sessions.events(session).stream()
                        .filter(event -> event.payload() instanceof RunCompleted).count() == expected);
                assertEquals(expected, requests.size(), "每条消息应产生一次真实上游请求");
                assertEquals(model, requests.getLast().path("model").asText());
                assertTrue(requests.getLast().path("stream").asBoolean());
                List<String> reported = sessions.events(session).stream().map(event -> event.payload())
                    .filter(ModelRequestCompleted.class::isInstance).map(ModelRequestCompleted.class::cast)
                    .map(event -> event.report().model()).toList();
                assertEquals(selected.subList(0, index + 1), reported, "用量事件也必须记录实际请求模型");
            }
            // 被拒绝的更新不改变正在使用的模型；非流式入口也读取同一配置。
            mvc.perform(put("/api/settings/model").contentType(MediaType.APPLICATION_JSON)
                .content("{\"model\":\"rejected-model\",\"endpoint\":\"file:/invalid\"}"))
                .andExpect(status().isBadRequest());
            assertEquals("已完成", client.chat(List.of(Message.user("非流式验证")), List.of()).content());
            assertEquals(selected.getLast(), requests.getLast().path("model").asText());
            assertFalse(requests.getLast().path("stream").asBoolean());
            assertTrue(authorization.stream().allMatch("Bearer fixture-key"::equals), "仅切换模型仍沿用原密钥");
        } finally {
            upstream.stop(0);
        }
    }
}
