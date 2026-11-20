package com.pilot.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.pilot.core.message.Attachment;
import com.pilot.core.message.Message;
import com.pilot.core.message.ToolCall;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CacheWireTest {
    private final JsonMapper json = new JsonMapper();

    @Test
    void rejectedMarkersRetryOnceAndKeepFutureRequestsCompatible() throws Exception {
        var bodies = new ArrayList<String>();
        var reports = new ArrayList<RequestReport>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(request);
            boolean rejected = request.contains("cache_control");
            String response = rejected ? "{\"error\":{\"message\":\"unsupported cache_control\"}}"
                : "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":10,\"prompt_cache_hit_tokens\":700,\"cache_creation_input_tokens\":0}}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(rejected ? 400 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var raw =
                new OpenAiCompatibleModelClient(HttpClient.newHttpClient(), new JsonMapper(), endpoint, "test", "alias")
                    .withProfile(ModelProfile.resolve(endpoint, "alias", "anthropic-compatible", "explicit", "", null,
                        null, "short", null));
            var measured = new MeteredModelClient(raw, "session", "main", List.of(), reports::add);
            var messages = List.of(Message.system("stable"), Message.user("hello"));
            assertEquals("ok", measured.chat(messages, List.of()).content());
            assertEquals("ok", measured.chat(messages, List.of()).content());
            assertEquals(3, bodies.size());
            assertTrue(bodies.getFirst().contains("cache_control"));
            assertFalse(bodies.get(1).contains("cache_control"));
            assertEquals(bodies.get(1), bodies.get(2));
            assertEquals(List.of("failed", "completed", "completed"),
                reports.stream().map(RequestReport::status).toList());
            assertFalse(reports.getFirst().usage().exact());
            assertNull(reports.getFirst().cost().total());
            assertEquals(700, reports.get(1).usage().cachedInputTokens());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void downgradeNeverReplaysAuthenticationOrGeneralFailures() {
        var raw = new OpenAiCompatibleModelClient(HttpClient.newHttpClient(), new JsonMapper(),
            URI.create("https://gateway.test"), "test", "alias");
        assertFalse(raw.disableRejectedCache(new ModelClientException("HTTP 401 unsupported cache_control")));
        assertFalse(raw.disableRejectedCache(new ModelClientException("HTTP 400 invalid messages")));
        assertFalse(raw.disableRejectedCache(new IllegalStateException("HTTP 400 unsupported cache_control")));
        assertTrue(raw.disableRejectedCache(new ModelClientException("HTTP 400 unsupported cache_control")));
        assertFalse(raw.disableRejectedCache(new ModelClientException("HTTP 400 unsupported cache_control")));
    }

    @Test
    void nestedSchemasAndToolArgumentsHaveStableWireOrder() {
        var raw = new OpenAiCompatibleModelClient(HttpClient.newHttpClient(), new JsonMapper(),
            URI.create("https://api.openai.com"), "test", "gpt-5.1");
        var schemaA = new LinkedHashMap<String, Object>();
        schemaA.put("z", Map.of("y", 1, "x", 2));
        schemaA.put("a", List.of("b", "a"));
        var schemaB = Map.<String, Object>of("a", List.of("b", "a"), "z", Map.of("x", 2, "y", 1));
        var json = new JsonMapper();
        var a = json.writeValueAsString(CanonicalJson.ordered(raw.requestBody(List.of(Message.user("hello")),
            List.of(new ToolDefinition("tool", "desc", schemaA)), true)));
        var b = json.writeValueAsString(CanonicalJson.ordered(raw.requestBody(List.of(Message.user("hello")),
            List.of(new ToolDefinition("tool", "desc", schemaB)), true)));
        assertEquals(a, b);
        assertTrue(a.contains("include_usage"));
        String first = ModelRequestScope.within(null, "session-a", false,
            () -> raw.requestBody(List.of(), List.of(), true).get("prompt_cache_key").toString());
        String again = ModelRequestScope.within(null, "session-a", false,
            () -> raw.requestBody(List.of(), List.of(), true).get("prompt_cache_key").toString());
        String other = ModelRequestScope.within(null, "session-b", false,
            () -> raw.requestBody(List.of(), List.of(), true).get("prompt_cache_key").toString());
        assertEquals(first, again);
        assertNotEquals(first, other);
        assertFalse(first.contains("session-a"));
    }

    /**
     * 运行时把状态提醒、工具截图作为 user 消息追加在历史尾部；它们不是用户新开的一轮，
     * 所以前面消息的线上 JSON 必须逐字节不变，当前轮 assistant 的 reasoning_content 也不能被剥离。
     */
    @Test
    void appendedInternalUserMessagesKeepTheWirePrefixAndReasoning() throws Exception {
        List<String> bodies = new ArrayList<>();
        HttpServer server = recordingServer(bodies);
        server.start();
        Path image = Files.createTempFile("pilot-cache-wire", ".png");
        Files.write(image, new byte[] {(byte)0x89, 'P', 'N', 'G'});
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var raw = client(endpoint);
            List<Message> base = List.of(Message.system("stable rules"), Message.user("开始"),
                Message.assistant("", List.of(new ToolCall("call-1", "read_file", Map.of("path", "A.java"))), "先看文件"),
                Message.tool("call-1", "class A {}"));
            raw.chat(base, List.of());
            raw.chat(append(base, Message.user("<system-reminder>\n[Runtime task context]\n步骤 1\n</system-reminder>")),
                List.of());
            raw.chat(append(base,
                Message.toolImages("read_file 截图", List.of(new Attachment("shot.png", "image/png", image.toString())))),
                List.of());

            assertEquals(3, bodies.size());
            JsonNode first = messages(bodies.get(0));
            for (int index = 1; index < bodies.size(); index++) {
                JsonNode later = messages(bodies.get(index));
                for (int position = 0; position < first.size(); position++) {
                    assertEquals(first.get(position).toString(), later.get(position).toString(),
                        "追加内部消息不得改变已有前缀消息（第 " + position + " 条）");
                }
                assertEquals("先看文件", later.get(2).path("reasoning_content").asString(), "内部提醒进来后当前轮 assistant 的思考不能掉");
            }
            assertTrue(messages(bodies.get(1)).path(messages(bodies.get(1)).size() - 1).path("content").asString()
                .contains("<system-reminder>"));
            assertTrue(messages(bodies.get(2)).path(messages(bodies.get(2)).size() - 1).path("content").toString()
                .contains("工具图片"));
        } finally {
            server.stop(0);
            Files.deleteIfExists(image);
        }
    }

    /** 下一条真实用户消息到来后才不再回传旧一轮的思考。 */
    @Test
    void stopsReplayingEarlierThinkingOnlyAfterTheNextRealUserMessage() throws Exception {
        List<String> bodies = new ArrayList<>();
        HttpServer server = recordingServer(bodies);
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var raw = client(endpoint);
            List<Message> history = List.of(Message.system("stable rules"), Message.user("第一问"),
                Message.assistant("", List.of(new ToolCall("c1", "read_file", Map.of("path", "A.java"))), "旧思考"),
                Message.tool("c1", "A"), Message.user("第二问"),
                Message.assistant("", List.of(new ToolCall("c2", "read_file", Map.of("path", "B.java"))), "新思考"),
                Message.tool("c2", "B"));
            raw.chat(history, List.of());
            raw.chat(append(history, Message.user("<system-reminder>\n状态变化\n</system-reminder>")), List.of());

            JsonNode body = messages(bodies.get(1));
            assertFalse(body.get(2).has("reasoning_content"), "旧一轮的思考不再回传");
            assertEquals("新思考", body.get(5).path("reasoning_content").asString(), "当前轮的思考继续回传");
        } finally {
            server.stop(0);
        }
    }

    /** Anthropic 同样以真实用户轮次为界：内部提醒不剥离当前轮 thinking，下一条真实提问才停回传。 */
    @Test
    void anthropicKeepsCurrentTurnThinkingUntilTheNextRealUserMessage() throws Exception {
        List<String> bodies = new ArrayList<>();
        HttpServer server = recordingServer(bodies);
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var raw = anthropic(endpoint);
            List<Message> history =
                List.of(Message.system("main rules"), Message.user("第一问"),
                    Message.assistant("", List.of(new ToolCall("c1", "read_file", Map.of("path", "A.java"))),
                        "旧思考" + AnthropicMessagesModelClient.THINKING_SPLIT + "old-signature"),
                    Message.tool("c1", "A"), Message.user("第二问"),
                    Message.assistant("", List.of(new ToolCall("c2", "read_file", Map.of("path", "B.java"))),
                        "新思考" + AnthropicMessagesModelClient.THINKING_SPLIT + "new-signature"),
                    Message.tool("c2", "B"));
            raw.chat(history, List.of());
            raw.chat(append(history, Message.user("<system-reminder>\n状态变化\n</system-reminder>")), List.of());

            assertEquals(2, bodies.size());
            List<String> first = thinkingTexts(messages(bodies.get(0)));
            List<String> later = thinkingTexts(messages(bodies.get(1)));
            assertEquals(List.of("新思考"), first, "只回传当前轮的 thinking");
            assertEquals(first, later, "尾部内部提醒不得剥离当前轮 thinking");
            for (int position = 0; position < messages(bodies.get(0)).size(); position++) {
                assertEquals(messages(bodies.get(0)).path(position).toString(),
                    messages(bodies.get(1)).path(position).toString(), "前面消息的字节不能变");
            }
        } finally {
            server.stop(0);
        }
    }

    /** 尾部临时 system 引导出现/消失时，Anthropic 顶层 system 必须字节一致。 */
    @Test
    void anthropicTailGuidanceNeverRewritesTheTopLevelSystem() throws Exception {
        List<String> bodies = new ArrayList<>();
        HttpServer server = recordingServer(bodies);
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var raw = anthropic(endpoint);
            List<Message> base = List.of(Message.system("main rules"), Message.system("legacy second system"),
                Message.user("你好"), Message.assistant("好的", List.of()), Message.user("继续"));
            raw.chat(base, List.of());
            raw.chat(append(base, Message.system("[Runtime guidance; applies only to this request]\n短一些"),
                Message.system("[路径规则]\n先读 AGENTS.md"), Message.system("phaseHint: 收尾")), List.of());

            assertEquals(2, bodies.size());
            JsonNode plain = json.readTree(bodies.get(0));
            JsonNode guided = json.readTree(bodies.get(1));
            assertEquals("main rules", plain.path("system").asString());
            assertEquals(plain.path("system").toString(), guided.path("system").toString(), "尾部临时引导不能进顶层 system");
            JsonNode tail = guided.path("messages").path(guided.path("messages").size() - 1);
            assertEquals("user", tail.path("role").asString());
            String tailText = tail.path("content").toString();
            assertTrue(tailText.contains("[Runtime guidance"), tailText);
            assertTrue(tailText.contains("[路径规则]"), tailText);
            assertTrue(tailText.contains("phaseHint"), tailText);
            assertTrue(alternates(guided.path("messages")), "引导消息必须遵守 user/assistant 交替");
            assertTrue(guided.path("messages").toString().contains("legacy second system"),
                "非首位 system 不能拼进顶层 system，而要随消息体下发");
            int head = plain.path("messages").size() - 1;
            for (int position = 0; position < head; position++) {
                assertEquals(plain.path("messages").path(position).toString(),
                    guided.path("messages").path(position).toString(), "前面消息的字节不能变");
            }
        } finally {
            server.stop(0);
        }
    }

    /** 三个适配器的输出上限都要跟 ModelCapacity.active() 一致，否则会和 AgentLoop 的准入预算偏差。 */
    @Test
    void adaptersReadTheActiveModelCapacity() {
        ModelCapacity.activate(ModelCapacity.of(32_000L, 4_000));
        try {
            int output = ModelCapacity.active().outputTokens();
            URI endpoint = URI.create("https://gateway.test");
            var chat = client(endpoint);
            assertEquals(output, chat.requestBody(List.of(Message.user("hi")), List.of(), false).get("max_tokens"));
            var anthropic = anthropic(endpoint);
            assertEquals(output, anthropic.requestBody(List.of(Message.user("hi")), List.of()).get("max_tokens"));
            var responses = new OpenAiResponsesModelClient(HttpClient.newHttpClient(), new JsonMapper(), endpoint,
                "key", "gpt-5.1", Duration.ofSeconds(10), ModelProfile.generic(), Set.of());
            assertEquals(output,
                responses.requestBody(List.of(Message.user("hi")), List.of()).get("max_output_tokens"));
        } finally {
            ModelCapacity.activate(null);
        }
    }

    /** 运行时注入的 user 消息不能算新的一轮：回传边界的判定就建立在这个分类上。 */
    @Test
    void runtimeInjectionsAreNotNewUserTurns() {
        assertTrue(Message.user("<system-reminder>\n状态\n</system-reminder>").isInternalUserMessage());
        assertTrue(Message.user("[系统] 请收尾").isInternalUserMessage());
        assertTrue(Message.toolImages("截图", List.of()).isInternalUserMessage());
        assertTrue(Message.user(Message.MODEL_SWITCH_MARKER + " 模型已切换为 model-b。按新模型继续。").isInternalUserMessage(),
            "MeteredModelClient 的切换提醒必须能被统一识别");
        assertTrue(Message.user("<system-reminder>\n[Runtime task context]\n步骤 1\n</system-reminder>")
            .isInternalUserMessage());
        assertFalse(Message.user("把 A 模块改完").isInternalUserMessage());
        assertTrue(Message.user("把 A 模块改完").isRealUserTurn());
        List<Message> history = List.of(Message.system("rules"), Message.user("开始"), Message.assistant("好", List.of()),
            Message.user("<system-reminder>\n状态\n</system-reminder>"));
        assertEquals(1, Message.lastRealUserTurnIndex(history), "尾部内部提醒不能推进真实用户轮次");
        assertEquals(-1, Message.lastRealUserTurnIndex(
            List.of(Message.system("rules"), Message.user(Message.MODEL_SWITCH_MARKER + " 切换"))), "没有真实用户输入时返回 -1");
    }

    private static List<String> thinkingTexts(JsonNode messages) {
        List<String> texts = new ArrayList<>();
        for (JsonNode message : messages) {
            for (JsonNode block : message.path("content")) {
                if ("thinking".equals(block.path("type").asString())) {
                    texts.add(block.path("thinking").asString());
                }
            }
        }
        return texts;
    }

    private static OpenAiCompatibleModelClient client(URI endpoint) {
        return new OpenAiCompatibleModelClient(HttpClient.newHttpClient(), new JsonMapper(), endpoint, "test", "alias");
    }

    private static AnthropicMessagesModelClient anthropic(URI endpoint) {
        return new AnthropicMessagesModelClient(HttpClient.newHttpClient(), new JsonMapper(), endpoint, "key",
            "claude-test", Duration.ofSeconds(10), ModelProfile.generic(), Set.of());
    }

    private static List<Message> append(List<Message> base, Message... extra) {
        List<Message> messages = new ArrayList<>(base);
        messages.addAll(List.of(extra));
        return messages;
    }

    private JsonNode messages(String body) {
        return json.readTree(body).path("messages");
    }

    private static boolean alternates(JsonNode messages) {
        String previous = null;
        for (JsonNode message : messages) {
            String role = message.path("role").asString();
            if (role.equals(previous)) {
                return false;
            }
            previous = role;
        }
        return true;
    }

    /** 同一端口同时回放 chat completions 与 messages 协议，测试只看请求体。 */
    private static HttpServer recordingServer(List<String> bodies) {
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("无法启动本地回放服务", e);
        }
        server.createContext("/v1/chat/completions", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] payload = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"ok\"}}]}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            try (var output = exchange.getResponseBody()) {
                output.write(payload);
            }
        });
        server.createContext("/v1/messages", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] payload = "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            try (var output = exchange.getResponseBody()) {
                output.write(payload);
            }
        });
        return server;
    }
}
