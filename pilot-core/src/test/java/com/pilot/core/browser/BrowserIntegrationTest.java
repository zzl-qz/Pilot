package com.pilot.core.browser;

import com.pilot.core.permission.PermissionDecision;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.tool.ToolCall;
import com.pilot.core.tool.ToolResult;
import com.pilot.core.workspace.PathValidator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实浏览器验收：Pilot 的 browser 工具打开一个本机页面，输入文字、上传两张图片、提交，
 * 刷新后恢复，再用页面文字、控制台和网络记录作证据。
 *
 * <p>缺少 Node、playwright-core 或 Chrome 的环境直接跳过。
 * 运行时目录默认取仓库的 {@code .pilot/browser}（服务启动时由 BrowserRuntime 安装），
 * 也可用 {@code -Dpilot.browser.home=...} 指定。
 */
class BrowserIntegrationTest {
    private static final String PAGE = """
            <!doctype html><html><head><meta charset="utf-8"><title>Pilot Demo</title></head><body>
            <h1>消息队列</h1>
            <input id="title" placeholder="消息内容">
            <input id="files" type="file" multiple accept="image/*">
            <button id="send">发送到队列</button>
            <ul id="queue"></ul>
            <script>
              console.error('boot-warning');
              const queue = document.getElementById('queue');
              function show(text) { const li = document.createElement('li'); li.textContent = text; queue.appendChild(li); }
              const saved = localStorage.getItem('queue');
              if (saved) show('已恢复: ' + saved);
              document.getElementById('send').onclick = async () => {
                const text = document.getElementById('title').value;
                const count = document.getElementById('files').files.length;
                const entry = text + ' / 图片 ' + count;
                show('已入队: ' + entry);
                localStorage.setItem('queue', entry);
                await fetch('/api/queue', { method: 'POST' });
              };
            </script></body></html>
            """;

    @TempDir
    Path workspace;
    @TempDir
    Path artifacts;

    private HttpServer server;
    private PlaywrightBackend backend;
    private BrowserTool tool;
    private String previousArtifacts;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        Path home = home();
        Assumptions.assumeTrue(home != null && Files.isDirectory(home.resolve("node_modules").resolve("playwright-core")),
                "没有安装 playwright-core（.pilot/browser）");
        Path node = findNode();
        Assumptions.assumeTrue(node != null, "没有找到 Node.js");
        Path script = home.resolve("browser-sidecar.mjs");
        try (InputStream in = getClass().getResourceAsStream("/browser/browser-sidecar.mjs")) {
            Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
        }
        // 测试只操作独立浏览器，不能捡到真实桌面壳遗留的 CDP 连接信息。
        backend = new PlaywrightBackend(node, script, artifacts, new ObjectMapper(), 120_000,
                Map.of("PILOT_DESKTOP_ENDPOINT", artifacts.resolve("no-desktop.json").toString(),
                        "PILOT_BROWSER_PREFER_BUNDLED", "false",
                        "PILOT_BROWSER_HEADLESS", "true"));
        Assumptions.assumeTrue(backend.probe().available(), "本机没有可用的 Chrome：" + backend.availability().reason());

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (exchange.getRequestURI().getPath().equals("/api/queue")) {
                byte[] body = "queue unavailable".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, body.length);
                exchange.getResponseBody().write(body);
            } else {
                byte[] body = PAGE.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";

        previousArtifacts = System.getProperty("pilot.artifacts.dir");
        System.setProperty("pilot.artifacts.dir", artifacts.toString());
        tool = new BrowserTool(backend, "it-session", new PathValidator(workspace));
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        if (backend != null) backend.close();
        if (previousArtifacts == null) System.clearProperty("pilot.artifacts.dir");
        else System.setProperty("pilot.artifacts.dir", previousArtifacts);
    }

    @Test
    void verifiesAPageEndToEndWithEvidence() throws IOException {
        for (String name : List.of("one.png", "two.png")) {
            ImageIO.write(new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB), "png", workspace.resolve(name).toFile());
        }

        String navigation = run("navigate", "url", base);
        assertTrue(navigation.startsWith("已打开，HTTP 200"), navigation);
        String snapshot = run("snapshot");
        assertTrue(snapshot.contains("消息队列"), snapshot);
        String titleRef = ref(snapshot, "textbox|input", "消息内容");
        String fileRef = ref(snapshot, "input", "type=file");
        String sendRef = ref(snapshot, "button", "发送到队列");

        run("fill", "ref", titleRef, "text", "你好 Pilot");
        assertTrue(run("upload", "ref", fileRef, "files", List.of("one.png", "two.png")).contains("上传 2 个文件"));
        run("click", "ref", sendRef);
        run("wait", "text", "已入队: 你好 Pilot / 图片 2");

        run("wait", "ms", 500); // 让 POST /api/queue 先落定，再刷新
        // 刷新后从 localStorage 恢复
        assertTrue(run("reload").startsWith("已刷新"));
        assertTrue(run("snapshot").contains("已恢复: 你好 Pilot / 图片 2"));

        ToolResult shot = tool.executeResult(Map.of("action", "screenshot"));
        assertEquals(1, shot.images().size(), shot.text());
        Path file = Path.of(shot.images().getFirst().path());
        assertTrue(Files.size(file) > 1000);
        assertTrue(file.startsWith(artifacts));

        String console = run("console");
        assertTrue(console.contains("[error] boot-warning"), console);
        String failed = run("network", "failedOnly", true);
        assertTrue(failed.contains("POST " + base + "api/queue → 500"), failed);

        assertEquals("Pilot Demo", run("evaluate", "expression", "document.title"));
    }

    @Test
    void sessionsAreIsolatedAndNonLoopbackNavigationIsRefused() {
        assertTrue(run("navigate", "url", base).startsWith("已打开"));
        run("evaluate", "expression", "localStorage.setItem('secret','a')");
        BrowserTool other = new BrowserTool(backend, "other-session", new PathValidator(workspace));
        assertTrue(other.execute(Map.of("action", "navigate", "url", base)).startsWith("已打开"));
        assertEquals("null", other.execute(Map.of("action", "evaluate", "expression", "localStorage.getItem('secret')")).strip());

        String refused = run("navigate", "url", "https://example.com/");
        assertTrue(refused.startsWith("ERROR: browser 只能打开本机地址"), refused);
        assertFalse(run("snapshot").isBlank());
    }

    @Test
    void permissionsDifferentiateObservationFromInteraction() {
        BrowserTool probe = tool;
        for (PermissionMode mode : PermissionMode.values()) {
            PolicyPermissionGate gate = new PolicyPermissionGate(mode);
            assertEquals(PermissionDecision.ALLOW,
                    gate.check(new ToolCall("1", "browser", Map.of("action", "snapshot")), probe).decision());
        }
        ToolCall click = new ToolCall("2", "browser", Map.of("action", "click", "ref", "e1"));
        assertEquals(PermissionDecision.DENY, new PolicyPermissionGate(PermissionMode.READ_ONLY).check(click, probe).decision());
        assertEquals(PermissionDecision.ASK, new PolicyPermissionGate(PermissionMode.ASK).check(click, probe).decision());
        assertEquals(PermissionDecision.ALLOW, new PolicyPermissionGate(PermissionMode.AUTO_EDIT).check(click, probe).decision());
        ToolCall evaluate = new ToolCall("3", "browser", Map.of("action", "evaluate", "expression", "1"));
        assertEquals(PermissionDecision.ASK, new PolicyPermissionGate(PermissionMode.AUTO_EDIT).check(evaluate, probe).decision());
        assertEquals(PermissionDecision.ALLOW, new PolicyPermissionGate(PermissionMode.AUTO).check(evaluate, probe).decision());
    }

    /** 镜像链路全流程：握手声明能力 → 首帧到达 → 交互坐标上报 → 停帧后不再产帧。 */
    @Test
    void mirrorStreamDeliversFramesActionsAndStopsCleanly() throws Exception {
        assertTrue(backend.mirrorCapable(), "sidecar 未声明镜像能力（ping capabilities）");

        ConcurrentLinkedQueue<String> kinds = new ConcurrentLinkedQueue<>();
        CountDownLatch firstFrame = new CountDownLatch(1);
        CountDownLatch action = new CountDownLatch(1);
        AtomicReference<JsonNode> framePayload = new AtomicReference<>();
        backend.setEventListener((sessionId, kind, payload) -> {
            kinds.add(kind);
            if ("mirror-frame".equals(kind) && framePayload.compareAndSet(null, payload)) {
                firstFrame.countDown();
            }
            if ("agent-action".equals(kind)) {
                action.countDown();
            }
        });

        assertTrue(run("navigate", "url", base).startsWith("已打开"));
        backend.mirrorControl("it-session", true, 960, 55);

        assertTrue(firstFrame.await(15, TimeUnit.SECONDS), "镜像首帧没有到达，收到的事件：" + kinds);
        JsonNode payload = framePayload.get();
        assertTrue(payload.path("frame").asString("").length() > 100, "帧体太小，不像真实 JPEG");
        assertTrue(payload.path("url").asString("").contains("127.0.0.1"), "帧里没有带上页面地址");
        assertTrue(kinds.contains("nav"), "导航事件缺失：" + kinds);

        // 镜像开着时智能体交互会上报坐标（前端涟漪用）
        String snapshot = run("snapshot");
        run("click", "ref", ref(snapshot, "button", "发送到队列"));
        assertTrue(action.await(10, TimeUnit.SECONDS), "交互坐标事件没有到达：" + kinds);

        // 停帧：最后一个订阅者断开的场景，此后不应再收到新帧
        backend.mirrorControl("it-session", false, 0, 0);
        int seen = kinds.size();
        run("fill", "ref", ref(snapshot, "textbox|input", "消息内容"), "text", "停帧后还能操作");
        Thread.sleep(1500);
        assertTrue(kinds.stream().skip(seen).noneMatch("mirror-frame"::equals),
                "mirror-stop 之后不应再有帧：" + kinds);

        // 置前不抛异常（无头环境下静默成功）
        backend.focusPage("it-session");
    }

    private String run(String action, Object... pairs) {
        Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("action", action);
        for (int i = 0; i + 1 < pairs.length; i += 2) args.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return tool.execute(args);
    }

    /** 在快照里找 `kind ... [ref=eN]` 且该行包含 needle 的元素。 */
    private static String ref(String snapshot, String kindPattern, String needle) {
        Pattern pattern = Pattern.compile("^\\s*- (?:" + kindPattern + ")\\b.*\\[ref=(e\\d+)].*$", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(snapshot);
        while (matcher.find()) {
            if (matcher.group().contains(needle)) return matcher.group(1);
        }
        throw new AssertionError("快照里找不到 " + kindPattern + " / " + needle + "：\n" + snapshot);
    }

    private static Path home() {
        String configured = System.getProperty("pilot.browser.home");
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 5 && dir != null; i++) {
            Path candidate = dir.resolve(".pilot").resolve("browser");
            if (Files.isDirectory(candidate)) return candidate;
            dir = dir.getParent();
        }
        return null;
    }

    static Path findNode() {
        String runtime = System.getProperty("pilot.runtime.bin", "");
        String path = System.getenv("PATH") == null ? "" : System.getenv("PATH");
        for (String dir : (runtime + File.pathSeparator + path).split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            for (String name : List.of("node", "node.exe")) {
                Path candidate = Path.of(dir, name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate;
            }
        }
        return null;
    }
}
