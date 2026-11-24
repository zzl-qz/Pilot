package com.pilot.core.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.tool.Availability;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.tool.ToolResult;
import com.pilot.core.workspace.PathValidator;

import tools.jackson.databind.ObjectMapper;

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
        assertTrue(registry.names().isEmpty(), "浏览器默认不占每次请求的参数说明");
        registry.enable(List.of("browser"));
        assertEquals(Set.of("browser"), registry.names());
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
        assertEquals(List.of(workspace.toRealPath().resolve("a.png").toString()),
            backend.commands.getLast().get("files"));

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
            if (previous == null) {
                System.clearProperty("pilot.artifacts.dir");
            } else {
                System.setProperty("pilot.artifacts.dir", previous);
            }
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
            "back", "forward", "close", "text", "links", "tabs", "inspect", "resize")) {
            assertTrue(tool.readOnly(Map.of("action", action)), action);
            assertFalse(tool.localInteraction(Map.of("action", action)), action);
        }
        for (String action : List.of("click", "dblclick", "right_click", "hover", "fill", "type", "press", "select",
            "upload", "download", "switch_tab", "close_tab")) {
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

        for (String action : List.of("click", "dblclick", "right_click", "hover", "fill", "type", "press", "select",
            "upload", "download", "switch_tab", "close_tab", "navigate", "reload", "back", "forward", "close",
            "evaluate", "resize")) {
            String result = tool.execute(Map.of("action", action, "url", "http://localhost:1", "ref", "e1", "text", "x",
                "key", "Enter", "values", List.of("a"), "files", List.of("a.txt"), "expression", "1"));
            assertTrue(result.startsWith("用户已接管浏览器"), action + " 应被拦截：" + result);
            assertFalse(result.startsWith("ERROR"), "拦截提示不应是 ERROR，避免模型当成故障重试：" + result);
            assertTrue(result.contains("恢复后先重新 snapshot 验证登录态"), result);
        }
        assertTrue(backend.commands.isEmpty(), "被拦截的动作不应触达浏览器");

        // 观察类放行：用户操作的进度正是智能体需要的上下文
        for (String action : List.of("snapshot", "console", "network", "wait", "inspect")) {
            String result = tool.execute(Map.of("action", action));
            assertFalse(result.startsWith("用户已接管"), action + " 观察动作不应被拦截：" + result);
        }
        assertFalse(backend.commands.isEmpty());

        // 恢复后一切照常
        backend.paused = false;
        assertEquals("ok", tool.execute(Map.of("action", "click", "ref", "e1")));
    }

    @Test
    void validatesViewportAndOffersFixedReadOnlyInspection() {
        BrowserTool tool = tool();
        assertEquals("ok", tool.execute(Map.of("action", "resize", "width", 1440, "height", 840)));
        assertEquals(Map.of("op", "resize", "width", 1440, "height", 840), backend.commands.getLast());
        for (Object width : List.of(0, 279, 1921, 390.5, "1440", Double.NaN)) {
            assertTrue(tool.execute(Map.of("action", "resize", "width", width, "height", 840)).startsWith("ERROR:"));
        }
        for (int height : List.of(0, 219, 1601)) {
            assertTrue(tool.execute(Map.of("action", "resize", "width", 390, "height", height)).startsWith("ERROR:"));
        }
        assertTrue(tool.execute(Map.of("action", "resize")).startsWith("ERROR:"));
        assertEquals(1, backend.commands.size());
        tool.execute(Map.of("action", "inspect", "selector", "#title", "expression", "document.body.remove()"));
        assertEquals(Map.of("op", "inspect", "selector", "#title"), backend.commands.getLast());
        assertFalse(tool.confirm(Map.of("action", "inspect")));
        assertTrue(tool.confirm(Map.of("action", "evaluate")));
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

    @Test
    void externalActionsRequireExplicitScopeAndDoNotForwardUserControlFlags() {
        BrowserTool browser = tool();
        assertTrue(browser.execute(Map.of("action", "navigate", "url", "https://example.com")).startsWith("ERROR:"));
        assertTrue(browser.execute(Map.of("action", "navigate", "url", "https://example.com", "external", true))
            .startsWith("ERROR:"));
        assertTrue(backend.commands.isEmpty());
        Map<String, Object> navigate = Map.of("action", "navigate", "url", "https://example.com", "external", true,
            "purpose", "查看公开岗位", "userInitiated", true);
        assertFalse(browser.readOnly(navigate));
        assertFalse(browser.confirm(navigate));
        assertEquals("ok", browser.execute(navigate));
        assertEquals(true, backend.commands.getLast().get("approvedExternal"));
        assertFalse(backend.commands.getLast().containsKey("userInitiated"));
        assertTrue(browser.execute(Map.of("action", "click", "ref", "e1", "external", true, "purpose", "提交最终内容"))
            .startsWith("ERROR:"));
        assertEquals("ok", browser.execute(Map.of("action", "click", "ref", "e1", "external", true, "purpose", "提交最终内容",
            "expected_url", "https://example.com/job/1")));
        assertEquals("https://example.com/job/1", backend.commands.getLast().get("expected_url"));
    }

    @Test
    void preservesExternalScopeAndUrlRejections() {
        BrowserTool browser = tool();
        for (String url : List.of("http://example.com/", "https://user:pass@example.com/", "https://127.0.0.1/",
            "https://foo.localhost/", "https://localhost/", "https://[::1]/")) {
            String result =
                browser.execute(Map.of("action", "navigate", "url", url, "external", true, "purpose", "查资料"));
            assertTrue(result.startsWith("ERROR: "), url + " -> " + result);
        }
        assertTrue(browser.execute(Map.of("action", "press", "key", "Enter", "external", true, "purpose", "提交"))
            .contains("外网交互需要 expected_url"));
        assertTrue(backend.commands.isEmpty());
    }

    @Test
    void mapsClickTabInputAndLogFamilies() {
        BrowserTool browser = tool();
        browser.execute(Map.of("action", "dblclick", "ref", "e1"));
        browser.execute(Map.of("action", "right_click", "selector", "#menu"));
        browser.execute(Map.of("action", "hover", "ref", "e2"));
        browser.execute(Map.of("action", "switch_tab", "tabId", "t7"));
        browser.execute(Map.of("action", "close_tab", "tabId", "t7"));
        browser.execute(Map.of("action", "type", "ref", "e3", "text", "搜索词", "submit", false));
        browser.execute(Map.of("action", "select", "ref", "e4", "values", "选项"));
        browser.execute(Map.of("action", "console", "level", "error", "clear", true));
        browser.execute(Map.of("action", "wait", "text", "加载中", "selector", "#done", "ms", -5));
        browser.execute(Map.of("action", "screenshot", "ref", "e5", "fullPage", false));

        assertEquals(Map.of("op", "click", "ref", "e1", "clickCount", 2), backend.commands.get(0));
        assertEquals(Map.of("op", "click", "selector", "#menu", "button", "right"), backend.commands.get(1));
        assertEquals(Map.of("op", "hover", "ref", "e2"), backend.commands.get(2));
        assertEquals(Map.of("op", "switch_tab", "tabId", "t7"), backend.commands.get(3));
        assertEquals(Map.of("op", "close_tab", "tabId", "t7"), backend.commands.get(4));
        assertEquals(Map.of("op", "type", "ref", "e3", "text", "搜索词"), backend.commands.get(5));
        assertEquals(Map.of("op", "select", "ref", "e4", "values", List.of("选项")), backend.commands.get(6));
        assertEquals(Map.of("op", "console", "level", "error", "clear", true), backend.commands.get(7));
        assertEquals(Map.of("op", "wait", "text", "加载中", "selector", "#done", "ms", 0L), backend.commands.get(8));
        // 截图目标与 fullPage=false 只影响 ref 传递：false 不写进命令，path 由 BrowserTool 追加
        assertEquals("e5", backend.commands.get(9).get("ref"));
        assertFalse(backend.commands.get(9).containsKey("fullPage"));
    }

    @Test
    void rejectsMissingTargetsAndUploadLimits() throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "x");
        BrowserTool browser = tool();
        for (String action : List.of("download", "fill", "type", "select", "check", "uncheck", "upload")) {
            String result = browser
                .execute(Map.of("action", action, "text", "x", "values", List.of("a"), "files", List.of("a.txt")));
            assertTrue(result.contains("需要 ref（来自 snapshot）或 selector"), action + " -> " + result);
        }
        String hoverMissing = browser.execute(Map.of("action", "hover"));
        assertTrue(hoverMissing.contains("需要 ref（来自 snapshot）、selector 或坐标 (x, y)"), hoverMissing);
        List<String> eleven = new ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            eleven.add("a.txt");
        }
        assertTrue(browser.execute(Map.of("action", "upload", "ref", "e1", "files", eleven))
            .startsWith("ERROR: upload 一次最多 10 个文件"));
        assertEquals("ok", browser.execute(Map.of("action", "upload", "ref", "e1", "files", eleven.subList(1, 11))));
        assertEquals(10, ((List<?>)backend.commands.getLast().get("files")).size());
        assertTrue(browser.execute(Map.of("action", "fill", "ref", "e1", "text", 42)).contains("需要 text"));
        assertEquals(1, backend.commands.size());
    }

    @Test
    void enforcesSubagentEvaluateLockPaymentHardBlockHandoffBrokerAndEvidence() {
        List<BrowserEvidenceRecord> captured = new ArrayList<>();
        BrowserHandoffBroker broker = new BrowserHandoffBroker();
        BrowserTool restricted = new BrowserTool(backend, "s1", new PathValidator(workspace))
            .withRunContext("parent-s1", "run-1", "bagent-1").withAllowEvaluate(false).withHandoffBroker(broker)
            .withEvidenceRecorder(captured::add).withHandoffTimeout(Duration.ofSeconds(5));

        assertTrue(restricted.execute(Map.of("action", "evaluate", "expression", "1+1")).contains("不允许执行 evaluate"));

        String blocked = restricted.execute(Map.of("action", "click", "ref", "e1", "external", true, "expected_url",
            "https://checkout.stripe.com/c/pay/cs_test", "purpose", "自动付款"));
        assertTrue(blocked.contains("handoff"), blocked);
        assertTrue(backend.commands.isEmpty(), "支付页自动点击不得发往后端");

        Thread responder = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline) {
                if (!broker.pending("parent-s1").isEmpty()) {
                    broker.resume("s1", "用户已完成登录");
                    return;
                }
                try {
                    Thread.sleep(15);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        });
        responder.start();
        String resumed = restricted.execute(Map.of("action", "handoff", "kind", "auth", "reason", "请完成登录"));
        assertTrue(resumed.contains("用户已完成浏览器接管"), resumed);
        assertTrue(resumed.contains("用户已完成登录"), resumed);
        assertTrue(backend.clearedOverlay);
        assertTrue(backend.invalidatedRefs);
        assertTrue(backend.commands.stream().anyMatch(cmd -> "describe".equals(cmd.get("op"))));
        assertTrue(captured.stream().anyMatch(rec -> "payment".equals(rec.gateKind())));
        assertTrue(captured.stream().anyMatch(rec -> "handoff".equals(rec.action()) && "ok".equals(rec.status())));

        BrowserTool full = tool();
        assertEquals("ok", full.execute(Map.of("action", "new_tab", "url", "http://127.0.0.1:5173/next")));
        assertEquals("new_tab", backend.commands.getLast().get("op"));
    }

    @Test
    void forwardsCheckUncheckScrollWaitUrlStateTypeClearAndTracksControlLock() {
        BrowserTool browser = tool();
        assertEquals("ok", browser.execute(Map.of("action", "check", "ref", "e1")));
        assertEquals("ok", browser.execute(Map.of("action", "uncheck", "selector", "#agree")));
        assertEquals("ok", browser.execute(Map.of("action", "scroll", "direction", "down", "amount", 420)));
        assertEquals("ok", browser.execute(Map.of("action", "type", "ref", "e2", "text", "新内容", "clear", true)));
        assertEquals("ok", browser.execute(
            Map.of("action", "wait", "url", "/dashboard", "selector", ".spinner", "state", "hidden", "ms", 1200)));

        assertEquals(Map.of("op", "check", "ref", "e1"), backend.commands.get(0));
        assertEquals(Map.of("op", "uncheck", "selector", "#agree"), backend.commands.get(1));
        assertEquals(Map.of("op", "scroll", "direction", "down", "amount", 420), backend.commands.get(2));
        assertEquals(Map.of("op", "type", "ref", "e2", "text", "新内容", "clear", true), backend.commands.get(3));
        assertEquals(Map.of("op", "wait", "url", "/dashboard", "selector", ".spinner", "state", "hidden", "ms", 1200L),
            backend.commands.get(4));

        assertTrue(backend.execute(
            "s1", Map.of("op", "snapshot"), Duration.ofSeconds(1)).text().equals("ok"));
        assertTrue(backend.controlActive);
        assertEquals("输入 e2", backend.controlStatus);

        assertTrue(browser.execute(Map.of("action", "scroll", "direction", "diagonal")).startsWith("ERROR:"));
        assertTrue(browser.execute(Map.of("action", "wait", "state", "invalid")).startsWith("ERROR:"));

        assertEquals("ok", browser.execute(Map.of("action", "close")));
        assertFalse(backend.controlActive);
    }

    @Test
    void forwardsDragCoordinatesDialogBatchAndProcessRecording() {
        List<BrowserEvidenceRecord> captured = new ArrayList<>();
        backend.recording = true;
        BrowserTool browser = tool().withEvidenceRecorder(captured::add);

        assertEquals("ok", browser.execute(
            Map.of("action", "click", "x", 180, "y", 240)));
        assertEquals("ok", browser.execute(
            Map.of("action", "hover", "x", 90, "y", 110)));
        assertEquals("ok", browser.execute(
            Map.of("action", "scroll", "x", 200, "y", 300, "direction", "down", "amount", 160)));
        assertEquals("ok", browser.execute(Map.of(
            "action", "drag", "ref", "e1", "targetRef", "e2", "frame", "f1", "dragSteps", 10)));
        assertEquals("ok", browser.execute(Map.of(
            "action", "drag", "x", 40, "y", 50, "targetX", 220, "targetY", 150)));
        assertEquals("ok", browser.execute(
            Map.of("action", "dialog", "accept", true, "promptText", "确认口令")));
        assertEquals("ok", browser.execute(Map.of("action", "dialog")));

        assertEquals(Map.of("op", "click", "x", 180, "y", 240), backend.commands.get(0));
        assertEquals(Map.of("op", "hover", "x", 90, "y", 110), backend.commands.get(1));
        assertEquals(Map.of("op", "scroll", "direction", "down", "amount", 160, "x", 200, "y", 300),
            backend.commands.get(2));
        assertEquals(Map.of("op", "drag", "ref", "e1", "targetRef", "e2", "frame", "f1", "steps", 10),
            backend.commands.get(3));
        assertEquals(Map.of("op", "drag", "x", 40, "y", 50, "targetX", 220, "targetY", 150),
            backend.commands.get(4));
        assertEquals(Map.of("op", "dialog", "accept", true, "promptText", "确认口令"),
            backend.commands.get(5));
        assertEquals(Map.of("op", "dialog"), backend.commands.get(6));

        assertEquals("ok", browser.execute(Map.of(
            "action", "batch",
            "stopOnError", false,
            "delayMs", 30,
            "steps", List.of(
                Map.of("action", "fill", "ref", "e3", "text", "batch-val"),
                Map.of("action", "dblclick", "ref", "e4"),
                Map.of("action", "wait", "ms", 20)))));
        Map<String, Object> batchCmd = backend.commands.get(7);
        assertEquals("batch", batchCmd.get("op"));
        assertEquals(false, batchCmd.get("stopOnError"));
        assertEquals(30, batchCmd.get("delayMs"));

        assertTrue(browser.execute(Map.of("action", "batch", "steps", List.of())).startsWith("ERROR:"));
        assertTrue(browser.execute(Map.of(
            "action", "batch", "steps", List.of(Map.of("action", "navigate", "url", "http://127.0.0.1"))))
            .startsWith("ERROR:"));
        assertTrue(browser.execute(Map.of(
            "action", "batch", "steps", List.of(Map.of("action", "click", "ref", "e1", "external", true))))
            .startsWith("ERROR:"));

        String prevArtifacts = System.getProperty("pilot.artifacts.dir");
        System.setProperty("pilot.artifacts.dir", artifacts.toString());
        try {
            assertEquals("ok", browser.execute(Map.of("action", "record", "mode", "start")));
            String stopText = browser.execute(Map.of("action", "record", "mode", "stop"));
            assertTrue(stopText.contains("replay.json") || "ok".equals(stopText), stopText);
            String recPath = String.valueOf(backend.commands.getLast().get("path"));
            assertTrue(recPath.startsWith(artifacts.toString()), recPath);
            assertTrue(recPath.endsWith(".replay.json"), recPath);
            assertTrue(captured.stream().anyMatch(
                rec -> "record".equals(rec.action()) && recPath.equals(rec.recordingPath())));
        } finally {
            if (prevArtifacts == null) {
                System.clearProperty("pilot.artifacts.dir");
            } else {
                System.setProperty("pilot.artifacts.dir", prevArtifacts);
            }
        }
    }

    private static final class FakeBackend implements BrowserBackend {
        private Availability availability = Availability.ready();
        private final List<Map<String, Object>> commands = new ArrayList<>();
        private final List<String> sessions = new ArrayList<>();
        private String failure;
        private boolean screenshot;
        private boolean recording;
        private boolean paused;
        private boolean clearedOverlay;
        private boolean invalidatedRefs;
        private boolean controlActive;
        private String controlStatus = "";

        @Override
        public Availability availability() {
            return availability;
        }

        @Override
        public boolean interactionsPaused(String sessionId) {
            return paused;
        }

        @Override
        public void clearOverlay(String sessionId) {
            clearedOverlay = true;
            controlActive = false;
            controlStatus = "";
        }

        @Override
        public void setAgentControl(String sessionId, boolean active, String status, boolean resume) {
            controlActive = active;
            controlStatus = status == null ? "" : status;
        }

        @Override
        public boolean agentControlled(String sessionId) {
            return controlActive;
        }

        @Override
        public void invalidateRefs(String sessionId) {
            invalidatedRefs = true;
        }

        @Override
        public Reply execute(String sessionId, Map<String, Object> command, Duration timeout) {
            if (failure != null) {
                throw new BrowserException(failure);
            }
            commands.add(new java.util.LinkedHashMap<>(command));
            sessions.add(sessionId);
            if (screenshot) {
                try {
                    Path path = Path.of(String.valueOf(command.get("path")));
                    Files.createDirectories(path.getParent());
                    Files.write(path, new byte[] {1, 2, 3});
                    return new Reply("已截图", path.toString());
                } catch (IOException e) {
                    throw new BrowserException(e.getMessage());
                }
            }
            if (recording && "record".equals(command.get("op")) && "stop".equals(command.get("mode"))) {
                try {
                    Path path = Path.of(String.valueOf(command.get("path")));
                    Files.createDirectories(path.getParent());
                    Files.writeString(path, "{\"version\":1,\"frames\":[],\"actions\":[]}");
                    return new Reply("ok", null, Map.of("recording", Map.of("path", path.toString())));
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
