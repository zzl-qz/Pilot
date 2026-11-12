package com.pilot.core.browser;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolArtifacts;
import com.pilot.core.tool.ToolResult;
import com.pilot.core.tool.ToolSupport;
import com.pilot.core.workspace.PathValidator;

/**
 * 在隔离的浏览器里验证本机 Web 应用：打开页面、读 DOM 快照、点击 / 输入 / 上传、截图，
 * 并查看控制台与网络记录。每个 Pilot 会话一个独立的浏览器上下文。
 *
 * <p>能力随运行时握手声明（{@link #availability()}）：浏览器没装或没起来时模型根本看不到这个工具。
 * 默认打开回环地址；公开 HTTPS 站点需 external=true 并逐次确认。上传的文件必须在工作区内。
 *
 * <p>权限按动作区分：看（导航 / 快照 / 截图 / 控制台 / 网络 / 等待）视为只读；
 * 点击、输入、选择、上传是本机交互（{@link LocalInteraction}），READ_ONLY 拒绝、ASK 审批、AUTO_EDIT 放行；
 * 在页面里执行任意脚本（evaluate）始终审批。
 */
public final class BrowserTool implements Tool, LocalInteraction {
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
    private static final int MAX_UPLOAD_FILES = 10;

    private final BrowserBackend backend;
    private final String sessionId;
    private final PathValidator paths;
    private final AtomicLong shots = new AtomicLong();

    public BrowserTool(BrowserBackend backend, String sessionId, PathValidator paths) {
        this.backend = backend;
        this.sessionId = sessionId == null || sessionId.isBlank() ? "default" : sessionId;
        this.paths = paths;
    }

    @Override
    public String name() {
        return "browser";
    }

    @Override
    public String description() {
        return promptDescription();
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
        return Map.of("type", "object", "properties", properties, "required", List.of("action"), "additionalProperties",
            false);
    }

    @Override
    public boolean readOnly() {
        return false;
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
        String error = build(action, args, command);
        if (error != null) {
            return ToolResult.text("ERROR: " + error);
        }

        Path screenshot = null;
        if ("screenshot".equals(action)) {
            screenshot = nextScreenshot();
            command.put("path", screenshot.toString());
        }
        BrowserBackend.Reply reply;
        try {
            reply = backend.execute(sessionId, command, timeout(action, args));
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

    @Override
    public boolean parallelSafe() {
        return false;
    }

    /** 校验并拼装 sidecar 命令；返回非空表示参数错误。 */
    private String build(String action, Map<String, Object> args, Map<String, Object> command) {
        if (Boolean.TRUE.equals(args.get("external"))) {
            String purpose = ToolSupport.stringArg(args, "purpose");
            if (purpose == null) {
                return "外网操作需要 purpose 说明目标、最终内容和影响";
            }
            if (!"navigate".equals(action) && ToolSupport.stringArg(args, "expected_url") == null) {
                return "外网交互需要 expected_url 绑定审批时的页面";
            }
            command.put("approvedExternal", true);
            copy(args, command, "expected_url");
        }
        switch (action) {
            case "navigate" -> {
                String url = ToolSupport.stringArg(args, "url");
                if (url == null) {
                    return "navigate 需要 url";
                }
                String problem = Boolean.TRUE.equals(args.get("external")) ? checkExternalUrl(url) : checkUrl(url);
                if (problem != null) {
                    return problem;
                }
                command.put("url", url);
            }
            case "click" -> {
                return target(args, command);
            }
            case "dblclick" -> {
                command.put("op", "click");
                command.put("clickCount", 2);
                return target(args, command);
            }
            case "right_click" -> {
                command.put("op", "click");
                command.put("button", "right");
                return target(args, command);
            }
            case "hover", "download" -> {
                String problem = target(args, command);
                if (problem != null) {
                    return problem;
                }
                if ("download".equals(action)) {
                    Path file = nextScreenshot().resolveSibling("download-" + shots.get() + ".bin");
                    command.put("path", file.toString());
                }
                return null;
            }
            case "switch_tab", "close_tab" -> {
                String tabId = ToolSupport.stringArg(args, "tabId");
                if (tabId == null) {
                    return action + " 需要 tabId";
                }
                command.put("tabId", tabId);
            }
            case "fill", "type" -> {
                String problem = target(args, command);
                if (problem != null) {
                    return problem;
                }
                Object text = args.get("text");
                if (!(text instanceof String value)) {
                    return action + " 需要 text";
                }
                command.put("text", value);
                if (Boolean.TRUE.equals(args.get("submit"))) {
                    command.put("submit", true);
                }
            }
            case "press" -> {
                String key = ToolSupport.stringArg(args, "key");
                if (key == null) {
                    return "press 需要 key";
                }
                command.put("key", key);
            }
            case "select" -> {
                String problem = target(args, command);
                if (problem != null) {
                    return problem;
                }
                List<String> values = strings(args.get("values"));
                if (values.isEmpty()) {
                    return "select 需要 values";
                }
                command.put("values", values);
            }
            case "upload" -> {
                String problem = target(args, command);
                if (problem != null) {
                    return problem;
                }
                List<String> files = strings(args.get("files"));
                if (files.isEmpty()) {
                    return "upload 需要 files";
                }
                if (files.size() > MAX_UPLOAD_FILES) {
                    return "upload 一次最多 " + MAX_UPLOAD_FILES + " 个文件";
                }
                List<String> resolved = new ArrayList<>();
                for (String file : files) {
                    try {
                        Path path = uploadable(file);
                        if (!Files.isRegularFile(path)) {
                            return "文件不存在或不是普通文件: " + file;
                        }
                        resolved.add(path.toString());
                    } catch (RuntimeException e) {
                        return "文件不在工作区内或不可读: " + file;
                    }
                }
                command.put("files", resolved);
            }
            case "resize" -> {
                for (String key : List.of("width", "height")) {
                    int min = "width".equals(key) ? 280 : 220;
                    int max = "width".equals(key) ? 1920 : 1600;
                    if (!(args.get(key) instanceof Number n) || !Double.isFinite(n.doubleValue())
                        || n.doubleValue() != Math.rint(n.doubleValue()) || n.doubleValue() < min
                        || n.doubleValue() > max) {
                        return "resize 的 " + key + " 必须是 " + min + "–" + max + " 的整数";
                    }
                    command.put(key, n.intValue());
                }
            }
            case "screenshot", "inspect" -> {
                if (args.get("ref") != null || args.get("selector") != null) {
                    String problem = target(args, command);
                    if (problem != null) {
                        return problem;
                    }
                }
                if (Boolean.TRUE.equals(args.get("fullPage"))) {
                    command.put("fullPage", true);
                }
            }
            case "console" -> {
                copy(args, command, "level");
                if (Boolean.TRUE.equals(args.get("clear"))) {
                    command.put("clear", true);
                }
            }
            case "network" -> {
                copy(args, command, "filter");
                if (Boolean.TRUE.equals(args.get("failedOnly"))) {
                    command.put("failedOnly", true);
                }
                if (Boolean.TRUE.equals(args.get("clear"))) {
                    command.put("clear", true);
                }
            }
            case "wait" -> {
                copy(args, command, "text");
                copy(args, command, "selector");
                if (args.get("ms") instanceof Number ms) {
                    command.put("ms", Math.max(0, Math.min(15_000, ms.longValue())));
                }
            }
            case "evaluate" -> {
                String expression = ToolSupport.stringArg(args, "expression");
                if (expression == null) {
                    return "evaluate 需要 expression";
                }
                command.put("expression", expression);
            }
            default -> {
                // reload / back / snapshot / close 没有参数
            }
        }
        return null;
    }

    private static String target(Map<String, Object> args, Map<String, Object> command) {
        String ref = ToolSupport.stringArg(args, "ref");
        String selector = ToolSupport.stringArg(args, "selector");
        if (ref == null && selector == null) {
            return "需要 ref（来自 snapshot）或 selector";
        }
        if (ref != null) {
            command.put("ref", ref);
        } else {
            command.put("selector", selector);
        }
        return null;
    }

    private static void copy(Map<String, Object> args, Map<String, Object> command, String key) {
        String value = ToolSupport.stringArg(args, key);
        if (value != null) {
            command.put(key, value);
        }
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    out.add(String.valueOf(item).strip());
                }
            }
        } else if (value instanceof String single && !single.isBlank()) {
            out.add(single.strip());
        }
        return out;
    }

    /** 上传来源：工作区内的文件，或本工具自己产出的截图。 */
    private Path uploadable(String raw) {
        try {
            return paths.resolve(raw);
        } catch (RuntimeException e) {
            Path artifacts = ToolArtifacts.directory("").toAbsolutePath().normalize();
            Path candidate = Path.of(raw).toAbsolutePath().normalize();
            if (candidate.startsWith(artifacts)) {
                return candidate;
            }
            throw e;
        }
    }

    static String checkUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return "url 格式不正确: " + url;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return "url 只支持 http / https: " + url;
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean loopback = host.equals("localhost") || host.endsWith(".localhost") || host.equals("127.0.0.1")
            || host.equals("[::1]") || host.equals("::1") || host.matches("127\\.\\d+\\.\\d+\\.\\d+");
        if (!loopback) {
            return "browser 只能打开本机地址（localhost / 127.0.0.1 / [::1]）: " + url + "；外部网页内容请用 web_fetch";
        }
        return null;
    }

    static String checkExternalUrl(String value) {
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                return "外网仅支持无用户名/密码的 HTTPS 地址";
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.equals("localhost") || host.endsWith(".localhost") || host.matches("[0-9.]+")
                || host.contains(":")) {
                return "外网流程不接受本机或 IP 地址；请使用公开 HTTPS 域名";
            }
            return null;
        } catch (URISyntaxException e) {
            return "外网 URL 无效";
        }
    }

    private static Duration timeout(String action, Map<String, Object> args) {
        return switch (action) {
            case "navigate", "reload", "back" -> Duration.ofSeconds(45);
            case "screenshot" -> Duration.ofSeconds(30);
            case "wait" -> Duration.ofSeconds(30);
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
