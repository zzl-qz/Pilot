package com.pilot.core.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.pilot.core.WindowsJdkLoopbackSensitive;
import com.pilot.core.message.ToolCall;
import com.pilot.core.permission.PermissionDecision;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.tool.ToolResult;
import com.pilot.core.workspace.PathValidator;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 真实浏览器验收：Pilot 的 browser 工具打开一个本机页面，输入文字、上传两张图片、提交，
 * 刷新后恢复，再用页面文字、控制台和网络记录作证据。
 *
 * <p>缺少 Node、playwright-core 或 Chrome 的环境直接跳过。
 * 运行时目录默认取仓库的 {@code .pilot/browser}（服务启动时由 BrowserRuntime 安装），
 * 也可用 {@code -Dpilot.browser.home=...} 指定。
 */
@WindowsJdkLoopbackSensitive
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
        Assumptions.assumeTrue(
            home != null && Files.isDirectory(home.resolve("node_modules").resolve("playwright-core")),
            "没有安装 playwright-core（.pilot/browser）");
        Path node = findNode();
        Assumptions.assumeTrue(node != null, "没有找到 Node.js");
        Path script = home.resolve("browser-sidecar.mjs");
        try (InputStream in = getClass().getResourceAsStream("/browser/browser-sidecar.mjs")) {
            Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
        }
        try (InputStream in = getClass().getResourceAsStream("/browser/browser-policy.mjs")) {
            Files.copy(in, home.resolve("browser-policy.mjs"), StandardCopyOption.REPLACE_EXISTING);
        }
        // 测试只操作独立浏览器，不能捡到真实桌面壳遗留的 CDP 连接信息。
        backend = new PlaywrightBackend(node, script, artifacts, new ObjectMapper(), 120_000,
            Map.of("PILOT_DESKTOP_ENDPOINT", artifacts.resolve("no-desktop.json").toString(),
                "PILOT_BROWSER_PREFER_BUNDLED", "false", "PILOT_BROWSER_HEADLESS", "true"));
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
        if (server != null) {
            server.stop(0);
        }
        if (backend != null) {
            backend.close();
        }
        if (previousArtifacts == null) {
            System.clearProperty("pilot.artifacts.dir");
        } else {
            System.setProperty("pilot.artifacts.dir", previousArtifacts);
        }
    }

    @Test
    void readsLayoutAndVerifiesThreeViewportsWithoutArbitraryScripts() throws IOException {
        run("navigate", "url", base);
        ObjectMapper mapper = new ObjectMapper();
        for (int width : List.of(1440, 768, 390)) {
            assertTrue(run("resize", "width", width, "height", 840).contains(width + "×840"));
            JsonNode info = mapper.readTree(run("inspect", "selector", "#title"));
            assertEquals(width, info.path("viewport").path("width").asInt());
            assertEquals(840, info.path("viewport").path("height").asInt());
            assertTrue(info.path("element").path("rect").path("width").asDouble() > 0);
            assertEquals("input", info.path("element").path("tag").asText());
            assertTrue(info.path("elements").size() >= 3, "概览包含可见表单控件的布局");
            assertTrue(info.path("hint").asText().contains("不需要 evaluate"));
            ToolResult shot = tool.executeResult(Map.of("action", "screenshot"));
            BufferedImage png = ImageIO.read(Path.of(shot.images().getFirst().path()).toFile());
            assertEquals(width, png.getWidth());
            assertEquals(840, png.getHeight());
        }
        assertTrue(run("inspect", "selector", "#missing").startsWith("ERROR:"));
    }

    @Test
    void verifiesAPageEndToEndWithEvidence() throws IOException {
        for (String name : List.of("one.png", "two.png")) {
            ImageIO.write(new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB), "png",
                workspace.resolve(name).toFile());
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
    void modelSnapshotAndScreenshotHideAgentOverlayAndSensitivePageFields() throws IOException {
        run("navigate", "url", base);
        run("evaluate", "expression",
            """
                (() => {
                  const field = document.createElement('input');
                  field.name = 'apiToken'; field.value = 'secret-token-value';
                  field.style.cssText = 'position:fixed;left:100px;top:100px;width:200px;height:40px;background:#00ff00;color:#00ff00';
                  document.body.append(field);
                  const frame = document.createElement('iframe');
                  frame.srcdoc = '<body style="margin:0;background:#00ff00"></body>';
                  frame.style.cssText = 'position:fixed;left:350px;top:100px;width:100px;height:60px;border:0';
                  document.body.append(frame);
                  return 'ready';
                })()
                """);

        String snapshot = run("snapshot");
        assertTrue(snapshot.contains("value=[已遮挡]"), snapshot);
        assertFalse(snapshot.contains("secret-token-value"), snapshot);

        String sendRef = ref(snapshot, "button", "发送到队列");
        run("click", "ref", sendRef);
        assertTrue(run("evaluate", "expression", "document.querySelector('[data-pilot-agent-overlay]')?.textContent")
            .contains("点击"));
        assertFalse(run("snapshot").contains("正在点击"));

        ToolResult result = tool.executeResult(Map.of("action", "screenshot"));
        BufferedImage screenshot = ImageIO.read(Path.of(result.images().getFirst().path()).toFile());
        assertEquals(0xFF171717, screenshot.getRGB(150, 120), "敏感输入框应被遮挡");
        assertEquals(0xFF171717, screenshot.getRGB(400, 130), "srcdoc iframe 应被遮挡");
    }

    @Test
    void withholdsSensitivePagesScrubsDiagnosticsAndReviewsDownloads() throws IOException {
        String login = run("navigate", "url", base + "login?token=login-secret-value");
        assertFalse(login.contains("login-secret-value"), login);
        assertTrue(run("snapshot").contains("敏感登录或支付页面已隐藏"));
        assertTrue(run("text").contains("敏感登录或支付页面已隐藏"));
        assertTrue(run("inspect").contains("敏感登录或支付页面已隐藏"));
        assertTrue(run("links").contains("敏感登录或支付页面已隐藏"));

        run("navigate", "url", base);
        run("evaluate", "expression",
            "console.error('Authorization: Bearer diagnostic-secret-123456'); fetch('/api/queue?access_token=oauth-secret-value')");
        String console = run("console");
        String network = run("network", "failedOnly", true);
        assertFalse(console.contains("diagnostic-secret-123456"), console);
        assertFalse(network.contains("oauth-secret-value"), network);
        assertTrue(network.contains("access_token"), network);

        run("evaluate", "expression", """
            (() => {
              let top = 10;
              for (const [id, name, data] of [
                ['good-download', 'report.txt', 'data:text/plain,hello'],
                ['bad-download', 'payload.exe', 'data:application/octet-stream,blocked'],
                ['disguised-download', 'disguised.txt', 'data:text/plain;base64,TVqQAA==']
              ]) {
                const link = document.createElement('a');
                link.id = id; link.href = data; link.download = name; link.textContent = name;
                link.style.cssText = `position:fixed;left:10px;top:${top}px`;
                top += 40;
                document.body.append(link);
              }
              return 'ready';
            })()
            """);
        String safeDownload = run("download", "selector", "#good-download");
        assertTrue(safeDownload.contains("report.txt"), safeDownload);
        Matcher savedPath = Pattern.compile("保存位置：(.+)").matcher(safeDownload);
        assertTrue(savedPath.find(), safeDownload);
        assertTrue(Files.isRegularFile(Path.of(savedPath.group(1))), safeDownload);
        assertTrue(run("download", "selector", "#bad-download").contains("已阻止下载危险文件类型"));
        assertTrue(run("download", "selector", "#disguised-download").contains("PE executable"));

        String checkout = run("navigate", "url", base + "checkout");
        assertTrue(checkout.contains("敏感登录或支付页面已隐藏") || checkout.contains("已打开"), checkout);
        assertTrue(run("evaluate", "expression", "document.title").contains("敏感"), "敏感页面禁止执行 evaluate");
        ToolResult sensitiveShot = tool.executeResult(Map.of("action", "screenshot", "selector", "body"));
        assertTrue(sensitiveShot.text().contains("敏感"), "敏感页面元素截图应受保护");
    }

    @Test
    void supportsMultiTabIsolatesLoopbackPortsClearsOverlayAndInvalidatesRefs() throws Exception {
        HttpServer otherPortServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        otherPortServer.createContext("/", exchange -> {
            byte[] body = "<html><body>other port</body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        otherPortServer.start();
        int otherPort = otherPortServer.getAddress().getPort();
        server.createContext("/redirect-cross-port", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + otherPort + "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        try {
            assertTrue(run("navigate", "url", base).startsWith("已打开"));
            String secondTab = run("new_tab", "url", base + "second");
            assertTrue(secondTab.contains("tab-2"), secondTab);
            String tabList = run("tabs");
            assertTrue(tabList.contains("tab-1") && tabList.contains("tab-2"), tabList);
            assertTrue(run("switch_tab", "tabId", "tab-1").contains("tab-1"));
            assertTrue(run("close_tab", "tabId", "tab-2").contains("已关闭标签 tab-2"));

            String snap = run("snapshot");
            String sendRef = ref(snap, "button", "发送到队列");
            backend.clearOverlay("it-session");
            backend.invalidateRefs("it-session");
            String staleClick = run("click", "ref", sendRef);
            assertTrue(staleClick.startsWith("ERROR:"), "invalidateRefs 后旧 ref 必须失效：" + staleClick);

            String crossRedirect = run("navigate", "url", base + "redirect-cross-port");
            assertTrue(crossRedirect.startsWith("ERROR:") || !crossRedirect.contains("other port"),
                "跨端口回环重定向必须被拦截：" + crossRedirect);
        } finally {
            otherPortServer.stop(0);
        }
    }

    @Test
    void sessionsAreIsolatedAndNonLoopbackNavigationIsRefused() {
        assertTrue(run("navigate", "url", base).startsWith("已打开"));
        run("evaluate", "expression", "localStorage.setItem('secret','a')");
        BrowserTool other = new BrowserTool(backend, "other-session", new PathValidator(workspace));
        assertTrue(other.execute(Map.of("action", "navigate", "url", base)).startsWith("已打开"));
        assertEquals("null",
            other.execute(Map.of("action", "evaluate", "expression", "localStorage.getItem('secret')")).strip());

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
        assertEquals(PermissionDecision.DENY,
            new PolicyPermissionGate(PermissionMode.READ_ONLY).check(click, probe).decision());
        assertEquals(PermissionDecision.ASK,
            new PolicyPermissionGate(PermissionMode.ASK).check(click, probe).decision());
        assertEquals(PermissionDecision.ALLOW,
            new PolicyPermissionGate(PermissionMode.AUTO_EDIT).check(click, probe).decision());
        ToolCall evaluate = new ToolCall("3", "browser", Map.of("action", "evaluate", "expression", "1"));
        assertEquals(PermissionDecision.ASK,
            new PolicyPermissionGate(PermissionMode.AUTO_EDIT).check(evaluate, probe).decision());
        assertEquals(PermissionDecision.ASK,
            new PolicyPermissionGate(PermissionMode.AUTO).check(evaluate, probe).decision());
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
        assertTrue(kinds.stream().skip(seen).noneMatch("mirror-frame"::equals), "mirror-stop 之后不应再有帧：" + kinds);

        // 置前不抛异常（无头环境下静默成功）
        backend.focusPage("it-session");
    }

    private String run(String action, Object... pairs) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("action", action);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            args.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return tool.execute(args);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void subscriptionsBeforePageCreationRespectCaptureAndStopWithoutLeaking(boolean captureInitially) throws Exception {
        AtomicInteger frames = new AtomicInteger();
        CountDownLatch firstFrame = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> urls = new ConcurrentLinkedQueue<>();
        backend.setEventListener((sessionId, kind, payload) -> {
            if ("mirror-frame".equals(kind)) {
                frames.incrementAndGet();
                firstFrame.countDown();
            }
            if ("nav".equals(kind)) {
                urls.add(payload.path("url").asString(""));
            }
        });
        backend.mirrorControl("it-session", true, 960, 55, captureInitially);
        assertTrue(run("navigate", "url", base).startsWith("已打开"));
        run("evaluate", "expression",
            "document.body.animate([{background:'#06f'},{background:'#f60'}],{duration:200,iterations:Infinity}); 'animated'");
        Thread.sleep(1000);
        assertTrue(urls.contains(base), "只订阅状态也必须获得当前网址");
        if (!captureInitially) {
            assertEquals(0, frames.get(), "只订阅状态时不能提前启动截图");
        }
        backend.mirrorControl("it-session", true, 960, 55, true);
        assertTrue(firstFrame.await(10, TimeUnit.SECONDS), "显式画面订阅应启动截图");
        backend.mirrorControl("it-session", true, 960, 55, false);
        Thread.sleep(300); // 排空已经传输的帧，再验证停帧后的稳定窗口。
        frames.set(0);
        Thread.sleep(1200);
        assertEquals(0, frames.get(), "停止后不得残留第二个 CDP 录屏会话");
    }

    @Test
    void automatesLongScrollableStickyFormShadowDomAndOcclusionCheck() {
        String formHtml = """
            <!doctype html><html><head><meta charset="utf-8"><title>Sticky Form</title></head>
            <body style="margin:0;font-family:sans-serif">
              <header id="sticky-header"
                style="position:fixed;top:0;left:0;right:0;height:80px;background:#111;color:#fff;z-index:20"
                onclick="window.__stickyClicks = (window.__stickyClicks || 0) + 1">粘性顶栏</header>
              <div style="height:1250px">长页面占位</div>
              <form onsubmit="return false" style="padding:20px">
                <input id="username" placeholder="用户名" value="旧值">
                <div id="rich-bio" contenteditable="true" role="textbox" aria-label="个人简介"
                  style="min-height:32px;border:1px solid #999;margin:8px 0"></div>
                <select id="plan"><option value="free">免费</option><option value="pro">专业版</option></select>
                <label><input id="agree" type="checkbox"> 同意协议</label>
                <div id="shadow-host"></div>
                <button id="submit-btn" type="button"
                  onclick="document.getElementById('result').textContent = '提交成功:'
                    + document.getElementById('username').value + ':'
                    + document.getElementById('rich-bio').textContent.trim() + ':'
                    + document.getElementById('plan').value + ':'
                    + document.getElementById('agree').checked">提交表单</button>
              </form>
              <div id="result"></div>
              <div id="blocker" style="display:none;position:fixed;inset:0;background:rgba(0,0,0,.5);z-index:50">
                遮罩弹窗
              </div>
              <script>
                window.__stickyClicks = 0;
                const host = document.getElementById('shadow-host');
                const root = host.attachShadow({ mode: 'open' });
                const btn = document.createElement('button');
                btn.id = 'shadow-btn';
                btn.textContent = '影子按钮';
                btn.onclick = () => { window.__shadowClicked = true; };
                root.appendChild(btn);
              </script>
            </body></html>
            """;
        server.createContext("/sticky-form", exchange -> {
            byte[] body = formHtml.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        assertTrue(run("navigate", "url", base + "sticky-form").startsWith("已打开"));
        String snap = run("snapshot");
        assertTrue(snap.contains("影子按钮"), "快照必须穿透开放 Shadow DOM：" + snap);

        run("type", "ref", ref(snap, "textbox|input", "用户名"), "text", "张三_pilot", "clear", true);
        run("fill", "ref", ref(snap, "textbox", "个人简介"), "text", "自动化测试简介");
        run("select", "ref", ref(snap, "combobox|select", "免费"), "values", List.of("pro"));
        String agreeRef = ref(snap, "checkbox|input", "agree");
        assertTrue(run("check", "ref", agreeRef).contains("checked=true"));
        assertTrue(run("uncheck", "ref", agreeRef).contains("checked=false"));
        assertTrue(run("check", "ref", agreeRef).contains("checked=true"));

        run("click", "ref", ref(snap, "button", "影子按钮"));
        assertEquals("true", run("evaluate", "expression", "String(window.__shadowClicked)"));

        // 弹窗遮挡提交按钮时必须报错阻止盲点
        run("evaluate", "expression", "document.getElementById('blocker').style.display='block'");
        String occluded = run("click", "ref", ref(snap, "button", "提交表单"));
        assertTrue(occluded.contains("被遮挡"), "弹窗遮挡必须被命中校验拦截：" + occluded);

        run("evaluate", "expression", "document.getElementById('blocker').style.display='none'");
        assertTrue(run("scroll", "direction", "up", "amount", 120).contains("已向 up 滚动"));
        assertTrue(run("click", "ref", ref(snap, "button", "提交表单")).startsWith("已点击"));
        assertTrue(run("wait", "text", "提交成功:张三_pilot:自动化测试简介:pro:true").contains("文本状态已达成"));
        assertEquals("0", run("evaluate", "expression", "String(window.__stickyClicks)"), "居中滚动后不得误点粘性顶栏");
    }

    @Test
    void takeControlPersistsAcrossNavigationAndStopsOnTrustedStopButtonClick() throws Exception {
        CountDownLatch stoppedEvent = new CountDownLatch(1);
        backend.setEventListener((sessionId, kind, payload) -> {
            if ("control-stopped".equals(kind) && "it-session".equals(sessionId)) {
                stoppedEvent.countDown();
            }
        });

        assertTrue(run("navigate", "url", base).startsWith("已打开"));
        Thread.sleep(1600);
        assertEquals("true", run("evaluate", "expression",
            "String(Boolean(document.querySelector('[data-pilot-agent-overlay]') && document.querySelector('[data-pilot-stop-btn]')))"),
            "Take Control 蓝框与 Stop 按钮不能再在 1.4s 后自动消失");

        assertTrue(run("navigate", "url", base + "second").startsWith("已打开"));
        assertEquals("true", run("evaluate", "expression",
            "String(Boolean(document.querySelector('[data-pilot-agent-overlay]') && document.querySelector('[data-pilot-stop-btn]')))"),
            "跨页面导航后必须自动重新挂载控制锁框");

        String coords = run("evaluate", "expression",
            "(() => { const r = document.querySelector('[data-pilot-stop-btn]').getBoundingClientRect(); "
                + "return Math.round(r.left + r.width / 2) + ',' + Math.round(r.top + r.height / 2); })()");
        String[] parts = coords.replace("\"", "").trim().split(",");
        int bx = Integer.parseInt(parts[0]);
        int by = Integer.parseInt(parts[1]);
        backend.execute("it-session", Map.of("op", "mouse", "action", "click", "x", bx, "y", by, "userInitiated", true),
            Duration.ofSeconds(5));

        assertTrue(stoppedEvent.await(5, TimeUnit.SECONDS), "点击页内 Stop 按钮必须触发 control-stopped 事件");
        String overlayAfterStop = backend.execute("it-session",
            Map.of("op", "evaluate", "expression", "String(Boolean(document.querySelector('[data-pilot-agent-overlay]')))",
                "userInitiated", true),
            Duration.ofSeconds(5)).text();
        assertEquals("false", overlayAfterStop, "点击 Stop 后必须立即移除页面蓝框和按钮");

        String blockedAfterStop = run("click", "selector", "body");
        assertTrue(blockedAfterStop.contains("停止自动控制"), "Stop 后后续驱动操作必须被锁定拦截：" + blockedAfterStop);
        String evalBlockedAfterStop = run("evaluate", "expression", "1");
        assertTrue(evalBlockedAfterStop.contains("停止自动控制"), "Stop 后任意脚本同样必须被拦截：" + evalBlockedAfterStop);

        backend.setAgentControl("it-session", false, "", true);
        assertTrue(run("click", "selector", "body").startsWith("已点击"), "用户恢复后应允许再次接管操作");
    }

    @Test
    void interactsInsideNestedSameOriginIframesAndHandlesDialogsAndFileChooser() throws IOException {
        registerHtml("/iframe-inner", """
            <!doctype html><html><meta charset="utf-8"><body>
              <input id="inner-name" placeholder="内层姓名">
              <label><input id="inner-agree" type="checkbox"> 同意条款</label>
              <button id="inner-ok" onclick="document.getElementById('inner-res').textContent =
                'INNER:' + document.getElementById('inner-name').value
                + ':' + document.getElementById('inner-agree').checked">确认提交</button>
              <div id="inner-res">idle</div>
            </body></html>
            """);
        registerHtml("/iframe-outer", """
            <!doctype html><html><meta charset="utf-8"><body>
              <h2>外层框架</h2>
              <iframe id="f-inner" title="内层窗口" src="/iframe-inner"
                style="width:420px;height:180px;border:1px solid #888"></iframe>
            </body></html>
            """);
        registerHtml("/nested-host", """
            <!doctype html><html><meta charset="utf-8"><body>
              <iframe id="f-outer" title="外层窗口" src="/iframe-outer"
                style="width:480px;height:240px;border:1px solid #888"></iframe>
              <div>
                <button id="btn-confirm"
                  onclick="document.getElementById('dlg-res').textContent = 'confirm:' + confirm('确认删除？')">
                  触发确认框</button>
                <button id="btn-prompt"
                  onclick="document.getElementById('dlg-res').textContent = 'prompt:' + prompt('输入口令', '')">
                  触发输入框</button>
                <div id="dlg-res">none</div>
              </div>
              <div>
                <button id="pick-upload">动态选择附件</button>
                <div id="upload-res">no-file</div>
              </div>
              <script>
                document.getElementById('pick-upload').addEventListener('click', () => {
                  const inp = document.createElement('input');
                  inp.type = 'file';
                  inp.onchange = () => {
                    document.getElementById('upload-res').textContent =
                      'uploaded:' + Array.from(inp.files).map((f) => f.name).join(',');
                  };
                  inp.click();
                });
              </script>
            </body></html>
            """);

        run("navigate", "url", base + "nested-host");
        run("wait", "ms", 250);
        String snap = run("snapshot");
        assertTrue(snap.contains("frame=f1") && snap.contains("frame=f2"), "快照必须展开双层同源 iframe：\n" + snap);

        String nameRef = ref(snap, "textbox|input", "内层姓名");
        String agreeRef = ref(snap, "checkbox|input", "type=checkbox");
        String okRef = ref(snap, "button", "确认提交");
        run("fill", "ref", nameRef, "text", "NestedOk");
        run("check", "ref", agreeRef);
        run("click", "ref", okRef);
        String innerRes = run("evaluate", "expression",
            "document.getElementById('f-outer').contentDocument"
                + ".getElementById('f-inner').contentDocument.getElementById('inner-res').textContent");
        assertEquals("INNER:NestedOk:true", innerRes);

        String confirmRef = ref(snap, "button", "触发确认框");
        String promptRef = ref(snap, "button", "触发输入框");
        run("dialog", "accept", false);
        run("click", "ref", confirmRef);
        assertEquals("confirm:false", run("evaluate", "expression",
            "document.getElementById('dlg-res').textContent"));

        run("dialog", "accept", true);
        run("click", "ref", confirmRef);
        assertEquals("confirm:true", run("evaluate", "expression",
            "document.getElementById('dlg-res').textContent"));

        run("dialog", "accept", true, "promptText", "pilot-ok");
        run("click", "ref", promptRef);
        assertEquals("prompt:pilot-ok", run("evaluate", "expression",
            "document.getElementById('dlg-res').textContent"));

        String dialogHistory = run("dialog");
        assertTrue(dialogHistory.contains("[confirm] dismissed"), dialogHistory);
        assertTrue(dialogHistory.contains("[confirm] accepted"), dialogHistory);
        assertTrue(dialogHistory.contains("pilot-ok"), dialogHistory);

        ImageIO.write(new BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB), "png",
            workspace.resolve("chooser.png").toFile());
        String pickRef = ref(snap, "button", "动态选择附件");
        assertTrue(run("upload", "ref", pickRef, "files", List.of("chooser.png")).contains("上传 1 个文件"));
        assertEquals("uploaded:chooser.png", run("evaluate", "expression",
            "document.getElementById('upload-res').textContent"));
    }

    @Test
    void supportsDragCoordinateFallbackBatchAndProcessRecording() throws Exception {
        StringBuilder checks = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            checks.append("<label><input type=\"checkbox\" id=\"cb").append(i).append("\">项").append(i)
                .append("</label>");
        }
        registerHtml("/phase-c-lab", """
            <!doctype html><html><meta charset="utf-8"><body style="margin:0">
              <div id="drag-src" draggable="true"
                style="position:fixed;left:40px;top:30px;width:100px;height:40px;background:#3b82f6">源卡片</div>
              <div id="drag-dst"
                style="position:fixed;left:220px;top:30px;width:120px;height:50px;border:2px dashed #666">放置区</div>
              <div id="drag-res" style="position:fixed;left:360px;top:30px">not-dropped</div>

              <div id="track" style="position:fixed;left:40px;top:110px;width:240px;height:30px;background:#eee"></div>
              <div id="knob" style="position:fixed;left:40px;top:110px;width:30px;height:30px;background:#10b981"></div>
              <div id="pointer-res" style="position:fixed;left:300px;top:110px">0</div>

              <canvas id="paint" width="120" height="60"
                style="position:fixed;left:40px;top:170px;width:120px;height:60px;background:#f59e0b"></canvas>
              <div id="canvas-res" style="position:fixed;left:180px;top:170px">canvas-idle</div>

              <div style="margin-top:260px">""" + checks + """
              </div>
              <script>
                const src = document.getElementById('drag-src');
                const dst = document.getElementById('drag-dst');
                src.addEventListener('dragstart', (e) => e.dataTransfer.setData('text/plain', 'card'));
                dst.addEventListener('dragover', (e) => e.preventDefault());
                dst.addEventListener('drop', (e) => {
                  e.preventDefault();
                  document.getElementById('drag-res').textContent = 'dropped:' + e.dataTransfer.getData('text/plain');
                });
                let dragging = false;
                document.getElementById('knob').addEventListener('mousedown', () => { dragging = true; });
                window.addEventListener('mousemove', (e) => {
                  if (!dragging) return;
                  document.getElementById('knob').style.left = Math.max(40, Math.round(e.clientX) - 15) + 'px';
                  document.getElementById('pointer-res').textContent = 'moved:' + Math.round(e.clientX);
                });
                window.addEventListener('mouseup', () => { dragging = false; });
                document.getElementById('paint').addEventListener('click', (e) => {
                  const r = e.currentTarget.getBoundingClientRect();
                  document.getElementById('canvas-res').textContent =
                    'canvas:' + Math.round(e.clientX - r.left) + ',' + Math.round(e.clientY - r.top);
                });
              </script>
            </body></html>
            """);
        registerHtml("/checkout", """
            <!doctype html><html><meta charset="utf-8"><body>
              <h1>敏感收银台</h1><input type="password" id="pin">
            </body></html>
            """);

        assertTrue(run("navigate", "url", base + "phase-c-lab").startsWith("已打开"));
        assertTrue(run("record", "mode", "start").startsWith("已开始录制"));

        assertTrue(run("drag", "selector", "#drag-src", "targetSelector", "#drag-dst").startsWith("已从"));
        assertEquals("dropped:card", run("evaluate", "expression",
            "document.getElementById('drag-res').textContent"));

        assertTrue(run("drag", "x", 55, "y", 125, "targetX", 210, "targetY", 125, "dragSteps", 8).startsWith("已从"));
        String pointerText = run("evaluate", "expression", "document.getElementById('pointer-res').textContent");
        assertTrue(pointerText.startsWith("moved:"), pointerText);
        assertTrue(Integer.parseInt(pointerText.substring("moved:".length())) >= 180, pointerText);

        assertTrue(run("click", "x", 90, "y", 200).startsWith("已按坐标点击"));
        assertTrue(run("evaluate", "expression", "document.getElementById('canvas-res').textContent")
            .startsWith("canvas:"));

        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            steps.add(Map.of("action", "check", "selector", "#cb" + i));
        }
        String batchText = run("batch", "steps", steps);
        assertTrue(batchText.contains("已完成批量操作 12 步"), batchText);
        assertEquals("12", run("evaluate", "expression",
            "String(document.querySelectorAll('input[type=checkbox]:checked').length)"));

        assertTrue(run("navigate", "url", base + "checkout").startsWith("已打开"));
        run("wait", "ms", 200);
        assertTrue(run("navigate", "url", base + "phase-c-lab").startsWith("已打开"));
        String stopOut = run("record", "mode", "stop");
        assertTrue(stopOut.contains("已保存过程录像"), stopOut);
        String replayLine = stopOut.lines().filter(l -> l.startsWith("录像文件: ")).findFirst().orElseThrow();
        Path replayPath = Path.of(replayLine.substring("录像文件: ".length()).strip());
        assertTrue(Files.isRegularFile(replayPath));
        JsonNode replay = new ObjectMapper().readTree(replayPath.toFile());
        assertTrue(replay.path("frames").size() >= 2, "录像必须至少采集 2 帧：" + replay.path("frames").size());
        assertTrue(replay.path("actions").size() >= 3, "录像必须包含动作标记：" + replay.path("actions").size());
        for (JsonNode frame : replay.path("frames")) {
            assertFalse(frame.path("data").asText().isBlank());
            assertFalse(frame.path("url").asText().contains("/checkout"),
                "敏感页面帧必须被跳过：" + frame.path("url").asText());
        }

        List<Map<String, Object>> longBatch = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            longBatch.add(Map.of("action", "wait", "ms", 120));
        }
        CompletableFuture<String> asyncBatch = CompletableFuture.supplyAsync(
            () -> run("batch", "steps", longBatch, "delayMs", 80));
        Thread.sleep(220);
        backend.stopControl("it-session");
        String aborted = asyncBatch.get(5, TimeUnit.SECONDS);
        assertTrue(aborted.startsWith("ERROR:") && (aborted.contains("停止") || aborted.contains("中止")), aborted);
        String blocked = run("click", "selector", "#cb1");
        assertTrue(blocked.contains("停止自动控制"), blocked);
        backend.setAgentControl("it-session", false, "", true);
        assertTrue(run("click", "selector", "#cb1").startsWith("已点击"));
    }

    private void registerHtml(String path, String html) {
        server.createContext(path, exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    /** 在快照里找 `kind ... [ref=eN]` 且该行包含 needle 的元素。 */
    private static String ref(String snapshot, String kindPattern, String needle) {
        Pattern pattern = Pattern.compile("^\\s*- (?:" + kindPattern + ")\\b.*\\[ref=(e\\d+)].*$", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(snapshot);
        while (matcher.find()) {
            if (matcher.group().contains(needle)) {
                return matcher.group(1);
            }
        }
        throw new AssertionError("快照里找不到 " + kindPattern + " / " + needle + "：\n" + snapshot);
    }

    private static Path home() {
        String configured = System.getProperty("pilot.browser.home");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 5 && dir != null; i++) {
            Path candidate = dir.resolve(".pilot").resolve("browser");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }

    static Path findNode() {
        String runtime = System.getProperty("pilot.runtime.bin", "");
        String path = System.getenv("PATH") == null ? "" : System.getenv("PATH");
        for (String dir : (runtime + File.pathSeparator + path).split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String name : List.of("node", "node.exe")) {
                Path candidate = Path.of(dir, name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }
}
