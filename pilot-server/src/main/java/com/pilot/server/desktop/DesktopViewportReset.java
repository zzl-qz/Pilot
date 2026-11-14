package com.pilot.server.desktop;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.pilot.server.browser.BrowserHome;

import tools.jackson.databind.ObjectMapper;

/**
 * 一轮智能体结束后，把桌面浏览器的设备模拟收回面板真实大小。
 * 桌面壳没开时什么都不做。
 */
@Component
public final class DesktopViewportReset {
    /** 只连本机的浏览器控制端口，连不上就立刻放弃，不拖慢窗口启动。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(300);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(400);
    private static final Pattern PORT = Pattern.compile("\"controlPort\"\\s*:\\s*(\\d+)");
    private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    private final BrowserHome browserHome;

    public DesktopViewportReset(BrowserHome browserHome) {
        this.browserHome = browserHome;
    }

    public void clear(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        Path file = browserHome.desktopEndpoint();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String json = Files.readString(file);
            Matcher port = PORT.matcher(json);
            Matcher token = TOKEN.matcher(json);
            if (!port.find() || !token.find()) {
                return;
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port.group(1) + "/emulate"))
                .timeout(REQUEST_TIMEOUT).header("content-type", "application/json")
                .header("x-pilot-token", token.group(1))
                .POST(HttpRequest.BodyPublishers.ofString(
                    new ObjectMapper().writeValueAsString(Map.of("sessionId", sessionId, "width", 0, "height", 0))))
                .build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // 桌面壳不在，或这一轮根本没模拟过视口
        }
    }
}
