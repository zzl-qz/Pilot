package com.pilot.core.browser;

import com.pilot.core.tool.Availability;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.tool.ToolResult;
import com.pilot.core.workspace.PathValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserToolTest {
    @TempDir
    Path workspace;
    @TempDir
    Path artifacts;

    private final FakeBackend backend = new FakeBackend();

    private BrowserTool tool() {
        return new BrowserTool(backend, "s1", new PathValidator(workspace));
    }

    @Test
    void capabilityFollowsBackendAvailability() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool());

        backend.availability = Availability.unavailable("未检测到 Chrome");
        assertTrue(registry.names().isEmpty());
        assertEquals("未检测到 Chrome", registry.unavailable().get("browser"));

        backend.availability = Availability.ready();
        assertEquals(java.util.Set.of("browser"), registry.names());
    }

    @Test
    void mapsActionsToSidecarCommands() {
        BrowserTool tool = tool();

        tool.execute(Map.of("action", "navigate", "url", "http://localhost:3000/app"));
        tool.execute(Map.of("action", "fill", "ref", "e3", "text", "hi", "submit", true));
        tool.execute(Map.of("action", "click", "selector", "#go"));
        tool.execute(Map.of("action", "network", "failedOnly", true, "filter", "/api"));
        tool.execute(Map.of("action", "wait", "ms", 999_999));

        assertEquals(Map.of("op", "navigate", "url", "http://localhost:3000/app"), backend.commands.get(0));
        assertEquals(Map.of("op", "fill", "ref", "e3", "text", "hi", "submit", true), backend.commands.get(1));
        assertEquals(Map.of("op", "click", "selector", "#go"), backend.commands.get(2));
        assertEquals(Map.of("op", "network", "filter", "/api", "failedOnly", true), backend.commands.get(3));
        assertEquals(15_000L, backend.commands.get(4).get("ms"));
        assertTrue(backend.sessions.stream().allMatch("s1"::equals));
    }

    @Test
    void rejectsBadArgumentsWithoutTouchingTheBrowser() {
        BrowserTool tool = tool();

        assertEquals("ERROR: 缺少参数 action", tool.execute(Map.of()));
        assertTrue(tool.execute(Map.of("action", "teleport")).startsWith("ERROR: 未知 action"));
        assertTrue(tool.execute(Map.of("action", "navigate")).contains("需要 url"));
        assertTrue(tool.execute(Map.of("action", "click")).contains("需要 ref"));
        assertTrue(tool.execute(Map.of("action", "fill", "ref", "e1")).contains("需要 text"));
        assertTrue(tool.execute(Map.of("action", "press")).contains("需要 key"));
        assertTrue(tool.execute(Map.of("action", "select", "ref", "e1")).contains("需要 values"));
        assertTrue(tool.execute(Map.of("action", "upload", "ref", "e1")).contains("需要 files"));
        assertTrue(tool.execute(Map.of("action", "evaluate")).contains("需要 expression"));
        assertTrue(backend.commands.isEmpty());
    }

    @Test
    void onlyLoopbackHttpAddressesCanBeOpened() {
        BrowserTool tool = tool();

        for (String url : List.of("https://example.com/", "http://192.168.1.5:8080/", "file:///etc/passwd",
                "javascript:alert(1)", "http://localhost.evil.com/", "ftp://localhost/x")) {
            String result = tool.execute(Map.of("action", "navigate", "url", url));
            assertTrue(result.startsWith("ERROR: "), url + " -> " + result);
        }
        for (String url : List.of("http://localhost:8080/", "https://127.0.0.1/", "http://[::1]:3000/x",
                "http://app.localhost:5173/", "http://127.0.0.2/")) {
            assertFalse(tool.execute(Map.of("action", "navigate", "url", url)).startsWith("ERROR: "), url);
        }
    }

    @Test
    void uploadsMustComeFromTheWorkspace() throws IOException {
        Files.writeString(workspace.resolve("a.png"), "x");
        BrowserTool tool = tool();

        String ok = tool.execute(Map.of("action", "upload", "ref", "e2", "files", List.of("a.png")));
        assertFalse(ok.startsWith("ERROR"), ok);
        assertEquals(List.of(workspace.toRealPath().resolve("a.png").toString()), backend.commands.getLast().get("files"));

        assertTrue(tool.execute(Map.of("action", "upload", "ref", "e2", "files", List.of("/etc/passwd")))
                .startsWith("ERROR: 文件不在工作区内"));
        assertTrue(tool.execute(Map.of("action", "upload", "ref", "e2", "files", List.of("../x.png")))
                .startsWith("ERROR: 文件不在工作区内"));
        assertTrue(tool.execute(Map.of("action", "upload", "ref", "e2", "files", List.of("missing.png")))
                .startsWith("ERROR: 文件不存在"));
        assertEquals(1, backend.commands.size());
    }

    @Test
    void screenshotIsReturnedAsAnImageAttachmentUnderTheArtifactsDirectory() throws IOException {
        String previous = System.getProperty("pilot.artifacts.dir");
        System.setProperty("pilot.artifacts.dir", artifacts.toString());
        try {
            backend.screenshot = true;

            ToolResult result = tool().executeResult(Map.of("action", "screenshot", "fullPage", true));

            assertEquals(1, result.images().size());
            assertEquals("image/png", result.images().getFirst().mimeType());
            Path file = Path.of(result.images().getFirst().path());
            assertTrue(Files.isRegularFile(file));
            assertTrue(file.startsWith(artifacts.resolve("browser").resolve("s1")), file.toString());
            assertEquals(true, backend.commands.getFirst().get("fullPage"));
            assertEquals(file.toString(), backend.commands.getFirst().get("path"));
        } finally {
            if (previous == null) System.clearProperty("pilot.artifacts.dir");
            else System.setProperty("pilot.artifacts.dir", previous);
        }
    }

    @Test
    void backendFailuresBecomeActionableErrors() {
        backend.failure = "无法连接 http://localhost:9: 连接被拒绝";

        String result = tool().execute(Map.of("action", "navigate", "url", "http://localhost:9"));

        assertEquals("ERROR: 无法连接 http://localhost:9: 连接被拒绝", result);
    }

    @Test
    void classifiesActionsForPermissions() {
        BrowserTool tool = tool();
        for (String action : List.of("navigate", "snapshot", "screenshot", "console", "network", "wait", "reload",
                "back", "close")) {
            assertTrue(tool.readOnly(Map.of("action", action)), action);
            assertFalse(tool.localInteraction(Map.of("action", action)), action);
        }
        for (String action : List.of("click", "fill", "type", "press", "select", "upload")) {
            assertFalse(tool.readOnly(Map.of("action", action)), action);
            assertTrue(tool.localInteraction(Map.of("action", action)), action);
        }
        assertFalse(tool.readOnly(Map.of("action", "evaluate")));
        assertFalse(tool.localInteraction(Map.of("action", "evaluate")));
        // 参数不合法时只会返回错误，不需要审批。
        assertTrue(tool.readOnly(Map.of()));
        assertTrue(tool.readOnly(Map.of("action", "teleport")));
    }

    /** 用户接管的拦截矩阵：交互/导航/脚本被温和拦下，观察类照常执行。 */
    @Test
    void userTakeoverBlocksInteractionsButNotObservations() {
        backend.paused = true;
        BrowserTool tool = tool();

        for (String action : List.of("click", "fill", "type", "press", "select", "upload",
                "navigate", "reload", "back", "close", "evaluate")) {
            String result = tool.execute(Map.of("action", action, "url", "http://localhost:1",
                    "ref", "e1", "text", "x", "key", "Enter", "values", List.of("a"), "files", List.of("a.txt"),
                    "expression", "1"));
            assertTrue(result.startsWith("用户已接管浏览器"), action + " 应被拦截：" + result);
            assertFalse(result.startsWith("ERROR"), "拦截提示不应是 ERROR，避免模型当成故障重试：" + result);
        }
        assertTrue(backend.commands.isEmpty(), "被拦截的动作不应触达浏览器");

        // 观察类放行：用户操作的进度正是智能体需要的上下文
        for (String action : List.of("snapshot", "console", "network", "wait")) {
            String result = tool.execute(Map.of("action", action));
            assertFalse(result.startsWith("用户已接管"), action + " 观察动作不应被拦截：" + result);
        }
        assertFalse(backend.commands.isEmpty());

        // 恢复后一切照常
        backend.paused = false;
        assertEquals("ok", tool.execute(Map.of("action", "click", "ref", "e1")));
    }

    @Test
    void realBackendReportsAReasonInsteadOfThrowingWhenTheRuntimeIsMissing() {
        PlaywrightBackend real = new PlaywrightBackend(workspace.resolve("no-node"), workspace.resolve("sidecar.mjs"),
                workspace.resolve("home"), new ObjectMapper());
        try {
            assertFalse(real.availability().available());

            Availability probed = real.probe();

            assertFalse(probed.available());
            assertTrue(probed.reason().contains("找不到 Node.js"), probed.reason());
            BrowserBackend.BrowserException error = assertThrows(BrowserBackend.BrowserException.class,
                    () -> real.execute("s", Map.of("op", "snapshot"), Duration.ofSeconds(1)));
            assertTrue(error.getMessage().startsWith("浏览器当前不可用："), error.getMessage());
        } finally {
            real.close();
        }
    }

    private static final class FakeBackend implements BrowserBackend {
        private Availability availability = Availability.ready();
        private final List<Map<String, Object>> commands = new ArrayList<>();
        private final List<String> sessions = new ArrayList<>();
        private String failure;
        private boolean screenshot;
        private boolean paused;

        @Override
        public Availability availability() {
            return availability;
        }

        @Override
        public boolean interactionsPaused(String sessionId) {
            return paused;
        }

        @Override
        public Reply execute(String sessionId, Map<String, Object> command, Duration timeout) {
            if (failure != null) throw new BrowserException(failure);
            commands.add(new java.util.LinkedHashMap<>(command));
            sessions.add(sessionId);
            if (screenshot) {
                try {
                    Path path = Path.of(String.valueOf(command.get("path")));
                    Files.createDirectories(path.getParent());
                    Files.write(path, new byte[]{1, 2, 3});
                    return new Reply("已截图", path.toString());
                } catch (IOException e) {
                    throw new BrowserException(e.getMessage());
                }
            }
            return new Reply("ok", null);
        }

        @Override
        public void closeSession(String sessionId) {
        }
    }
}
