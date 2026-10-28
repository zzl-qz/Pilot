package com.pilot.core.tool;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 只读网页搜索。请求本机 SearXNG 的 JSON 接口，不需要搜索密钥。 */
public final class WebSearchTool implements Tool {
    private static final URI DEFAULT_ENDPOINT = URI.create("http://127.0.0.1:8888");

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final URI endpoint;

    public WebSearchTool(HttpClient client, ObjectMapper mapper) {
        this(client, mapper, DEFAULT_ENDPOINT);
    }

    public WebSearchTool(HttpClient client, ObjectMapper mapper, URI endpoint) {
        this.client = client;
        this.mapper = mapper;
        this.endpoint = endpoint;
    }

    @Override public String name() { return "web_search"; }
    @Override public String description() { return promptDescription(); }
    @Override public boolean readOnly() { return true; }
    @Override public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties", Map.of(
                "query", Map.of("type", "string", "description", "搜索关键词"),
                "count", Map.of("type", "integer", "description", "结果数量，1 到 10")),
                "required", List.of("query"), "additionalProperties", false);
    }

    @Override public String execute(Map<String, Object> args) {
        String query = ToolSupport.stringArg(args, "query");
        if (query == null || query.isBlank()) {
            return "ERROR: 缺少参数 query";
        }
        int count = 5;
        Object requested = args.get("count");
        if (requested instanceof Number number) {
            count = Math.max(1, Math.min(10, number.intValue()));
        }
        try {
            String base = endpoint.toString().endsWith("/")
                    ? endpoint.toString().substring(0, endpoint.toString().length() - 1)
                    : endpoint.toString();
            URI uri = URI.create(base + "/search?q="
                    + URLEncoder.encode(query.strip(), StandardCharsets.UTF_8) + "&format=json");
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                return "ERROR: 搜索服务返回 HTTP " + response.statusCode();
            }
            JsonNode results = mapper.readTree(response.body()).path("results");
            List<String> lines = new ArrayList<>();
            for (JsonNode item : results) {
                if (lines.size() == count) {
                    break;
                }
                String title = item.path("title").asText("").strip();
                String url = item.path("url").asText("").strip();
                String snippet = item.path("content").asText("").strip();
                if (title.isEmpty() && url.isEmpty()) {
                    continue;
                }
                lines.add((lines.size() + 1) + ". " + (title.isEmpty() ? "(无标题)" : title)
                        + "\n   URL: " + url
                        + (snippet.isEmpty() ? "" : "\n   " + snippet));
            }
            return lines.isEmpty() ? "未找到网页结果。" : String.join("\n", lines);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "ERROR: 搜索请求被中断";
        } catch (Exception e) {
            if (down(e)) {
                return "ERROR: 本地搜索未启动。把 pilot.web-search.local 设为 true 后重启。";
            }
            return "ERROR: 搜索请求失败: " + e.getClass().getSimpleName();
        }
    }

    private static boolean down(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof java.net.ConnectException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.contains("Connection refused")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
