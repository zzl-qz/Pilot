package com.pilot.core.browser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import com.pilot.core.loop.Attachment;
import com.pilot.core.tool.Availability;
import com.pilot.core.tool.LocalInteraction;
import com.pilot.core.tool.PromptedTool;
import com.pilot.core.tool.ToolArtifacts;
import com.pilot.core.tool.ToolNames;
import com.pilot.core.tool.ToolResult;
import com.pilot.core.tool.ToolSupport;
import com.pilot.core.workspace.PathValidator;

/**
 * 在隔离的浏览器里验证本机 Web 应用：打开页面、读 DOM 快照、点击 / 输入 / 上传、截图，
 * 并查看控制台与网络记录。独立浏览器上下文按会话隔离存储；桌面标签按会话归属，共享工作区浏览器分区。
 *
 * <p>能力随运行时握手声明（{@link #availability()}）：浏览器没装或没起来时模型根本看不到这个工具。
 * 默认打开回环地址；公开 HTTPS 站点需 external=true 并逐次确认。上传的文件必须在工作区内。
 *
 * <p>权限按动作区分：看（导航 / 快照 / 截图 / 控制台 / 网络 / 等待）视为只读；
 * 点击、输入、选择、上传是本机交互（{@link LocalInteraction}），READ_ONLY 拒绝、ASK 审批、AUTO_EDIT 放行；
 * 在页面里执行任意脚本（evaluate）始终审批。
 */
public final class BrowserTool extends PromptedTool implements LocalInteraction {
    private static final Set<String> OBSERVATIONS = Set.of("navigate", "reload", "back", "forward", "snapshot",
        "screenshot", "console", "network", "wait", "close", "text", "links", "tabs", "inspect", "resize");
    private static final Set<String> INTERACTIONS = Set.of("click", "dblclick", "right_click", "hover", "fill", "type",
        "press", "select", "upload", "download", "switch_tab", "close_tab");
    /** 用户接管（paused）时拦截的动作：交互类 + 会打断用户的导航/关闭/脚本。观察类放行。 */
    private static final Set<String> PAUSED_BLOCKS =
        Set.of("navigate", "reload", "back", "forward", "close", "evaluate", "click", "dblclick", "right_click",
            "hover", "fill", "type", "press", "select", "upload", "download", "switch_tab", "close_tab", "resize");
    private static final List<String> ACTIONS =
        List.of("navigate", "reload", "back", "forward", "snapshot", "click", "dblclick", "right_click", "hover",
            "fill", "type", "press", "select", "upload", "download", "screenshot", "console", "network", "text",
            "links", "tabs", "switch_tab", "close_tab", "wait", "evaluate", "close", "inspect", "resize");
    private static final int MAX_SCREENSHOTS_KEPT = 40;

    private final BrowserBackend backend;
    private final String sessionId;
    private final PathValidator paths;
    private final AtomicLong shots = new AtomicLong();

    public BrowserTool(BrowserBackend backend, String sessionId, PathValidator paths) {
        super(ToolNames.BROWSER);
        this.backend = backend;
        this.sessionId = sessionId == null || sessionId.isBlank() ? "default" : sessionId;
        this.paths = paths;
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", Map.of("type", "string", "enum", ACTIONS, "description",
            "要执行的动作；先 navigate，再 snapshot 取得元素 ref，之后用 ref 操作"));
        properties.put("url",
            Map.of("type", "string", "description", "navigate 地址：默认回环；外网公开 HTTPS 需 external=true 和 purpose"));
        properties.put("external",
            Map.of("type", "boolean", "description", "外网站点导航/操作必须设 true；每次操作都需用户即时确认，普通本机动作不能绕过"));
        properties.put("expected_url", Map.of("type", "string", "description", "外网交互的当前完整页面 URL，审批后页面变化则拒绝执行"));
        properties.put("purpose", Map.of("type", "string", "description", "外网操作的目标、最终提交内容和影响；提交申请/发送前明确列出对象与内容"));
        properties.put("ref",
            Map.of("type", "string", "description", "元素引用，形如 e3，来自最近一次 snapshot；页面变化后旧 ref 可能失效，需要重新 snapshot"));
        properties.put("selector", Map.of("type", "string", "description", "ref 之外的备选：CSS 选择器（取第一个匹配）"));
        properties.put("text", Map.of("type", "string", "description", "fill / type 要输入的文本；wait 时表示等待页面出现的文字"));
        properties.put("submit", Map.of("type", "boolean", "description", "fill / type 之后是否按 Enter 提交"));
        properties.put("key", Map.of("type", "string", "description", "press 的按键，例如 Enter、Escape、Control+A"));
        properties.put("values",
            Map.of("type", "array", "items", Map.of("type", "string"), "description", "select 要选的选项（value 或可见文本）"));
        properties.put("files", Map.of("type", "array", "items", Map.of("type", "string"), "description",
            "upload 的文件，工作区内的相对路径；ref 指向 <input type=file>"));
        properties.put("fullPage", Map.of("type", "boolean", "description", "screenshot 是否截取整页，默认只截可视区域"));
        properties.put("width", Map.of("type", "integer", "minimum", 280, "maximum", 1920, "description",
            "resize 的视口宽度（CSS 像素），如桌面 1440、平板 768、手机 390"));
        properties.put("height",
            Map.of("type", "integer", "minimum", 220, "maximum", 1600, "description", "resize 的视口高度（CSS 像素），例如 840"));
        properties.put("expression",
            Map.of("type", "string", "description", "evaluate 在页面内执行的 JavaScript 表达式，返回值会以 JSON 返回"));
        properties.put("filter", Map.of("type", "string", "description", "network 按 URL 子串过滤"));
        properties.put("failedOnly", Map.of("type", "boolean", "description", "network 只看失败或 4xx/5xx 的请求"));
        properties.put("level", Map.of("type", "string", "description", "console 只看某一类：error、warning、log、pageerror 等"));
        properties.put("clear", Map.of("type", "boolean", "description", "console / network 读取后清空记录"));
        properties.put("ms", Map.of("type", "integer", "description", "wait 的最长等待毫秒数（最大 15000）"));
        return objectSchema(properties, List.of("action"));
    }

    @Override
    public boolean readOnly(Map<String, Object> args) {
        String action = action(args);
        // 参数不合法时只会返回错误说明，不必为此审批。
        return action == null || !ACTIONS.contains(action) || OBSERVATIONS.contains(action);
    }

    @Override
    public boolean localInteraction(Map<String, Object> args) {
        String action = action(args);
        return action != null && INTERACTIONS.contains(action);
    }

    @Override
    public Availability availability() {
        return backend.availability();
    }

    @Override
    public boolean confirm(Map<String, Object> args) {
        return args != null && (Boolean.TRUE.equals(args.get("external")) || "evaluate".equals(action(args)));
    }

    @Override
    public String approvalDescription(Map<String, Object> args) {
        return "浏览器动作只授权本次调用。请核对目标页面、元素、最终文本和影响：\n"
            + new tools.jackson.databind.ObjectMapper().writeValueAsString(args);
    }

    @Override
    public String execute(Map<String, Object> args) {
        return executeResult(args).text();
    }

    @Override
    public ToolResult executeResult(Map<String, Object> args) {
        String action = action(args);
        if (action == null) {
            return ToolResult.text("ERROR: 缺少参数 action");
        }
        if (!ACTIONS.contains(action)) {
            return ToolResult.text("ERROR: 未知 action: " + action + "；可用：" + String.join(", ", ACTIONS));
        }
        // 用户接管：交互与导航被温和拦下（普通提示而非 ERROR，模型理解为等待用户，不会重试风暴）；
        // 观察类放行——用户操作的进度恰是智能体需要的上下文。
        if (backend.interactionsPaused(sessionId) && PAUSED_BLOCKS.contains(action)) {
            return ToolResult.text("用户已接管浏览器并暂停了智能体的页面操作。"
                + "snapshot / screenshot / console / network / wait 等观察动作仍可用（可了解用户当前进度）；" + "交互与导航动作请等用户恢复后再试。");
        }
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("op", action);
        String error = BrowserCommand.build(action, args, command, paths);
        if (error != null) {
            return ToolResult.text("ERROR: " + error);
        }

        if ("screenshot".equals(action)) {
            command.put("path", nextScreenshot().toString());
        } else if ("download".equals(action)) {
            command.put("path", nextScreenshot().resolveSibling("download-" + shots.get() + ".bin").toString());
        }
        BrowserBackend.Reply reply;
        try {
            reply = backend.execute(sessionId, command, timeout(action));
        } catch (BrowserBackend.BrowserException e) {
            return ToolResult.text("ERROR: " + e.getMessage());
        }
        if (reply.screenshot() == null) {
            return ToolResult.text(reply.text());
        }
        Path file = Path.of(reply.screenshot());
        String mime =
            file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png") ? "image/png" : "image/jpeg";
        prune(file.getParent());
        return ToolResult.withImages(reply.text() + "\n截图文件: " + file,
            List.of(new Attachment(file.getFileName().toString(), mime, file.toString())));
    }

    private static Duration timeout(String action) {
        return switch (action) {
            case "navigate", "reload", "back" -> Duration.ofSeconds(45);
            case "screenshot", "wait" -> Duration.ofSeconds(30);
            default -> Duration.ofSeconds(25);
        };
    }

    private Path nextScreenshot() {
        String bucket = "browser/" + sessionId.replaceAll("[^A-Za-z0-9._-]", "_");
        Path dir = ToolArtifacts.directory(bucket);
        return dir.resolve("shot-" + System.currentTimeMillis() + "-" + shots.incrementAndGet() + ".png");
    }

    /** 截图只留最近若干张，防止长会话把数据目录撑满。 */
    private static void prune(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> sorted = files.filter(Files::isRegularFile)
                .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed()).toList();
            for (int i = MAX_SCREENSHOTS_KEPT; i < sorted.size(); i++) {
                Files.deleteIfExists(sorted.get(i));
            }
        } catch (IOException ignored) {
            // 清理失败不影响本次结果
        }
    }

    private static String action(Map<String, Object> args) {
        String value = ToolSupport.stringArg(args, "action");
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
