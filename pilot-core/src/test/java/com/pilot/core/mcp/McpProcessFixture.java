package com.pilot.core.mcp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 真实子进程 fixture；只依赖当前 JDK，不依赖 shell 或外部 MCP 服务。 */
public final class McpProcessFixture {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path marker = Path.of(args[1]);
        ObjectMapper mapper = new ObjectMapper();
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(8000);
                System.exit(0);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        watchdog.setDaemon(true);
        watchdog.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            JsonNode message = mapper.readTree(line);
            String method = message.path("method").asString("");
            if (method.equals("initialize")) {
                reply(mapper, message, Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of()));
            } else if (method.equals("notifications/initialized") && mode.equals("block")) {
                Files.writeString(marker, Long.toString(ProcessHandle.current().pid()));
                Thread.sleep(3500);
                return;
            } else if (method.equals("tools/call")) {
                Files.writeString(marker, Long.toString(ProcessHandle.current().pid()));
                if (mode.equals("hang")) {
                    Thread.sleep(3500);
                    return;
                }
                reply(mapper, message, Map.of("content", List.of(Map.of("type", "text", "text", "ok"))));
            }
        }
    }

    private static void reply(ObjectMapper mapper, JsonNode message, Object result) {
        System.out.println(
            mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", message.path("id").asLong(), "result", result)));
        System.out.flush();
    }
}
