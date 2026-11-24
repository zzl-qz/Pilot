package com.pilot.core.browser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import com.pilot.core.message.Attachment;
import com.pilot.core.message.ToolNames;
import com.pilot.core.tool.Availability;
import com.pilot.core.tool.LocalInteraction;
import com.pilot.core.tool.PromptedTool;
import com.pilot.core.tool.ToolArtifacts;
import com.pilot.core.tool.ToolResult;
import com.pilot.core.tool.ToolSchemas;
import com.pilot.core.tool.ToolSupport;
import com.pilot.core.workspace.PathValidator;

/**
 * 在隔离的浏览器里验证本机 Web 应用：打开页面、读 DOM 快照、点击 / 输入 / 上传、截图，
 * 并查看控制台与网络记录。独立浏览器上下文按会话隔离存储；桌面标签按会话归属，共享工作区浏览器分区。
 *
 * <p>能力随运行时握手声明（{@link #availability()}）：浏览器没装或没起来时模型根本看不到这个工具。
 * 默认打开回环地址。公开 HTTPS 在完全放行时直接打开；其他档位仍要确认。
 * 在页面里执行任意脚本（evaluate）始终审批。上传的文件必须在工作区内。
 *
 * <p>权限按动作区分：看（导航 / 快照 / 截图 / 控制台 / 网络 / 等待）视为只读；
 * 点击、输入、选择、上传是本机交互（{@link LocalInteraction}），READ_ONLY 拒绝、ASK 审批、AUTO_EDIT 放行；
 * 在页面里执行任意脚本（evaluate）始终审批。
 */
public final class BrowserTool extends PromptedTool implements LocalInteraction {
    private static final Set<String> OBSERVATIONS =
        Set.of("navigate", "new_tab", "handoff", "reload", "back", "forward", "snapshot", "screenshot", "console",
            "network", "wait", "close", "text", "links", "tabs", "inspect", "resize", "record");
    private static final Set<String> INTERACTIONS = Set.of("click", "dblclick", "right_click", "hover", "fill", "type",
        "press", "select", "check", "uncheck", "scroll", "drag", "dialog", "batch", "upload", "download",
        "switch_tab", "close_tab");
    /** 用户接管（paused）时拦截的动作：交互类 + 会打断用户的导航/新标签/关闭/脚本。观察类与 handoff 放行。 */
    private static final Set<String> PAUSED_BLOCKS = Set.of("navigate", "new_tab", "reload", "back", "forward", "close",
        "evaluate", "click", "dblclick", "right_click", "hover", "fill", "type", "press", "select", "check", "uncheck",
        "scroll", "drag", "dialog", "batch", "upload", "download", "switch_tab", "close_tab", "resize");
    private static final List<String> ACTIONS = List.of("navigate", "new_tab", "handoff", "reload", "back", "forward",
        "snapshot", "click", "dblclick", "right_click", "hover", "fill", "type", "press", "select", "check", "uncheck",
        "scroll", "drag", "dialog", "batch", "upload", "download", "screenshot", "record", "console", "network", "text",
        "links", "tabs", "switch_tab", "close_tab", "wait", "evaluate", "close", "inspect", "resize");
    private static final List<String> ACTIONS_NO_EVALUATE =
        ACTIONS.stream().filter(action -> !"evaluate".equals(action)).toList();
    private static final int MAX_SCREENSHOTS_KEPT = 40;
    private static final int MAX_DOWNLOADS_KEPT = 40;
    private static final int MAX_RECORDINGS_KEPT = 20;
    /** 打开、刷新、后退要等页面加载，给得最宽。 */
    private static final Duration NAVIGATION_TIMEOUT = Duration.ofSeconds(45);
    /** 截图和显式等待。 */
    private static final Duration CAPTURE_TIMEOUT = Duration.ofSeconds(30);
    /** 其余点击、输入等普通操作。 */
    private static final Duration ACTION_TIMEOUT = Duration.ofSeconds(25);
    private static final Duration HANDOFF_TIMEOUT = Duration.ofMinutes(10);

    private final BrowserBackend backend;
    private final String sessionId;
    private final PathValidator paths;
    private final AtomicLong shots = new AtomicLong();
    private String parentSessionId;
    private String runId = "";
    private String agentId = "";
    private BrowserEvidenceRecorder evidenceRecorder = BrowserEvidenceRecorder.none();
    private BrowserHandoffBroker handoffBroker;
    private boolean allowEvaluate = true;
    private Duration handoffTimeout = HANDOFF_TIMEOUT;

    public BrowserTool(BrowserBackend backend, String sessionId, PathValidator paths) {
        super(ToolNames.BROWSER);
        this.backend = backend;
        this.sessionId = sessionId == null || sessionId.isBlank() ? "default" : sessionId;
        this.parentSessionId = this.sessionId;
        this.paths = paths;
    }

    public BrowserTool withRunContext(String parentSessionId, String runId, String agentId) {
        if (parentSessionId != null && !parentSessionId.isBlank()) {
            this.parentSessionId = parentSessionId.strip();
        }
        this.runId = runId == null ? "" : runId.strip();
        this.agentId = agentId == null ? "" : agentId.strip();
        return this;
    }

    public BrowserTool withEvidenceRecorder(BrowserEvidenceRecorder recorder) {
        this.evidenceRecorder = recorder == null ? BrowserEvidenceRecorder.none() : recorder;
        return this;
    }

    public BrowserTool withHandoffBroker(BrowserHandoffBroker broker) {
        this.handoffBroker = broker;
        return this;
    }

    public BrowserTool withAllowEvaluate(boolean allowEvaluate) {
        this.allowEvaluate = allowEvaluate;
        return this;
    }

    BrowserTool withHandoffTimeout(Duration timeout) {
        if (timeout != null && !timeout.isNegative() && !timeout.isZero()) {
            this.handoffTimeout = timeout;
        }
        return this;
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", Map.of("type", "string", "enum", allowEvaluate ? ACTIONS : ACTIONS_NO_EVALUATE,
            "description", "要执行的动作；先 navigate，再 snapshot 取得元素 ref，之后用 ref 精确操作（自动居中滚动与遮挡校验）"));
        properties.put("url", ToolSchemas.string("navigate / new_tab 地址（默认回环；外网公开 HTTPS 需 external=true 和 purpose）；wait 时可作等待包含的 URL"));
        properties.put("external", ToolSchemas.bool("打开公开 HTTPS 时设 true。完全放行直接打开；其他档位仍要确认。登录和验证码仍要用户在页面上完成"));
        properties.put("expected_url", ToolSchemas.string("外网交互的当前完整页面 URL，审批后页面变化则拒绝执行"));
        properties.put("purpose", ToolSchemas.string("外网操作的目标、最终提交内容和影响；提交申请/发送前明确列出对象与内容"));
        properties.put("ref", ToolSchemas.string("元素引用，形如 e3，来自最近一次 snapshot；页面变化后旧 ref 可能失效，需要重新 snapshot"));
        properties.put("selector", ToolSchemas.string("ref 之外的备选：CSS 选择器（取第一个匹配）"));
        properties.put("frame", ToolSchemas.string("可选：目标所属同源 iframe 编号（如 f1 或 f1>f2）；来自 snapshot 输出，使用 ref 时可自动匹配"));
        properties.put("x", Map.of("type", "integer", "minimum", 0, "maximum", 4096,
            "description", "视觉坐标横坐标（CSS 像素）；Canvas/自定义控件无 DOM ref 时可作 click/hover/drag/scroll 坐标兜底"));
        properties.put("y", Map.of("type", "integer", "minimum", 0, "maximum", 4096,
            "description", "视觉坐标纵坐标（CSS 像素）"));
        properties.put("targetRef", ToolSchemas.string("drag 终点元素引用（来自 snapshot）"));
        properties.put("targetSelector", ToolSchemas.string("drag 终点 CSS 选择器"));
        properties.put("targetX", Map.of("type", "integer", "minimum", 0, "maximum", 4096,
            "description", "drag 终点横坐标（CSS 像素）"));
        properties.put("targetY", Map.of("type", "integer", "minimum", 0, "maximum", 4096,
            "description", "drag 终点纵坐标（CSS 像素）"));
        properties.put("dragSteps", Map.of("type", "integer", "minimum", 2, "maximum", 40,
            "description", "drag 平滑移动步数，默认 12"));
        properties.put("accept", ToolSchemas.bool("dialog 预设下一次 confirm/prompt/alert 确认(true)或取消(false)"));
        properties.put("promptText", ToolSchemas.string("dialog 为 prompt 且 accept=true 时填入的文本"));
        properties.put("steps", Map.of("type", "array",
            "items", Map.of("type", "object"),
            "description", "batch 批量子动作数组（1–50 项，每项含 action 与对应参数，如 click/fill/check/select/scroll/drag/wait/dialog）"));
        properties.put("stopOnError", ToolSchemas.bool("batch 单步失败时是否立即中止，默认 true"));
        properties.put("delayMs", Map.of("type", "integer", "minimum", 0, "maximum", 1000,
            "description", "batch 各步之间的等待毫秒数（0–1000）"));
        properties.put("mode", Map.of("type", "string", "enum", List.of("start", "stop", "status"),
            "description", "record 动作模式：start 开始录屏、stop 停止并保存可回放录像、status 查看录制计数"));
        properties.put("tabId", ToolSchemas.string("标签编号，来自 tabs 动作（用于 switch_tab / close_tab）"));
        properties.put("kind", Map.of("type", "string", "enum", List.of("auth", "captcha", "payment", "manual"),
            "description", "handoff 的接管类型：auth（登录/MFA）、captcha（人机验证）、payment（支付）、manual（人工协助）"));
        properties.put("reason", ToolSchemas.string("handoff 请求用户接管的原因与希望用户在页面完成的操作"));
        properties.put("text", ToolSchemas.string("fill / type 要输入的文本；wait 时表示等待页面出现或消失的文字"));
        properties.put("submit", ToolSchemas.bool("fill / type 之后是否按 Enter 提交"));
        properties.put("key", ToolSchemas.string("press 的按键，例如 Enter、Escape、Control+A"));
        properties.put("values",
            Map.of("type", "array", "items", Map.of("type", "string"), "description", "select 要选的选项（value 或可见文本）"));
        properties.put("direction", Map.of("type", "string", "enum", List.of("up", "down", "left", "right"),
            "description", "scroll 的滚动方向；仅传 ref/selector 不传 direction 时将该元素居中滚入视口"));
        properties.put("amount", Map.of("type", "integer", "minimum", 1, "maximum", 5000,
            "description", "scroll 的滚动像素距离（默认约为视口高度的 75%）"));
        properties.put("state", Map.of("type", "string", "enum", List.of("visible", "hidden"),
            "description", "wait 等待 text/selector 达到的状态：visible（默认，出现）或 hidden（消失）"));
        properties.put("files", Map.of("type", "array", "items", Map.of("type", "string"), "description",
            "upload 的文件，工作区内的相对路径；ref 指向 <input type=file>"));
        properties.put("fullPage", ToolSchemas.bool("screenshot 是否截取整页，默认只截可视区域"));
        properties.put("width", Map.of("type", "integer", "minimum", 280, "maximum", 1920, "description",
            "resize 的视口宽度（CSS 像素），如桌面 1440、平板 768、手机 390"));
        properties.put("height",
            Map.of("type", "integer", "minimum", 220, "maximum", 1600, "description", "resize 的视口高度（CSS 像素），例如 840"));
        properties.put("expression", ToolSchemas.string("evaluate 在页面内执行的 JavaScript 表达式，返回值会以 JSON 返回"));
        properties.put("filter", ToolSchemas.string("network 按 URL 子串过滤"));
        properties.put("failedOnly", ToolSchemas.bool("network 只看失败或 4xx/5xx 的请求"));
        properties.put("level", ToolSchemas.string("console 只看某一类：error、warning、log、pageerror 等"));
        properties.put("clear", ToolSchemas.bool("type 输入前是否清空原内容；或 console / network 读取后清空记录"));
        properties.put("ms", ToolSchemas.integer("wait 的最长等待毫秒数（最大 15000）"));
        return ToolSchemas.object(properties, List.of("action"));
    }

    @Override
    public boolean readOnly(Map<String, Object> args) {
        if (external(args)) {
            return false;
        }
        String action = action(args);
        // 参数不合法时只会返回错误说明，不必为此审批。
        return action == null || !ACTIONS.contains(action) || OBSERVATIONS.contains(action);
    }

    @Override
    public boolean localInteraction(Map<String, Object> args) {
        if (external(args)) {
            return false;
        }
        String action = action(args);
        return action != null && INTERACTIONS.contains(action);
    }

    @Override
    public Availability availability() {
        return backend.availability();
    }

    @Override
    public boolean confirm(Map<String, Object> args) {
        String action = action(args);
        if ("evaluate".equals(action)) {
            return true;
        }
        if ("download".equals(action) && external(args)) {
            return true;
        }
        if ("upload".equals(action) && BrowserCommand.hasSensitiveUploadFile(args)) {
            return true;
        }
        String targetUrl = targetUrl(args);
        return action != null && INTERACTIONS.contains(action)
            && (BrowserCommand.isAuthUrl(targetUrl) || BrowserCommand.isPaymentUrl(targetUrl));
    }

    private static boolean external(Map<String, Object> args) {
        return args != null && Boolean.TRUE.equals(args.get("external"));
    }

    @Override
    public String approvalDescription(Map<String, Object> args) {
        String action = action(args);
        String targetUrl = targetUrl(args);
        StringBuilder sb = new StringBuilder("浏览器动作只授权本次调用。请核对目标与影响：\n");
        sb.append("- 操作：").append(action == null ? "未知" : action).append('\n');
        if (!targetUrl.isEmpty()) {
            sb.append("- 目标页面：").append(targetUrl).append('\n');
        }
        String ref = ToolSupport.stringArg(args, "ref");
        String selector = ToolSupport.stringArg(args, "selector");
        if (ref != null || selector != null) {
            sb.append("- 元素目标：").append(ref != null ? ref : selector).append('\n');
        }
        String purpose = ToolSupport.stringArg(args, "purpose");
        if (purpose != null) {
            sb.append("- 操作目的：").append(purpose).append('\n');
        }
        if ("upload".equals(action)) {
            List<String> files = BrowserCommand.uploadSummaries(args, paths);
            if (!files.isEmpty()) {
                sb.append("- 上传文件：").append(String.join("、", files)).append('\n');
            }
            if (BrowserCommand.hasSensitiveUploadFile(args)) {
                sb.append("- 风险提示：包含疑似敏感本地文件（如环境变量或密钥文件），确认是否允许上传。\n");
            }
        }
        if ("evaluate".equals(action)) {
            sb.append("- 风险提示：将在页面上下文执行 JavaScript 表达式。\n");
        } else if (external(args) && "download".equals(action)) {
            sb.append("- 风险提示：外网下载将写入任务临时目录并做安全审查。\n");
        } else if (BrowserCommand.isAuthUrl(targetUrl) || BrowserCommand.isPaymentUrl(targetUrl)) {
            sb.append("- 风险提示：目标属于敏感登录或支付页面，建议由用户接管完成。\n");
        }
        return sb.toString().stripTrailing();
    }

    @Override
    public Map<String, Object> approvalBinding(Map<String, Object> args) {
        String action = action(args);
        String targetUrl = targetUrl(args);
        String origin = BrowserCommand.originOf(targetUrl);
        Map<String, Object> binding = new LinkedHashMap<>();
        if (action != null) {
            binding.put("browserAction", action);
        }
        if (origin != null) {
            binding.put("browserOrigin", origin);
        }
        boolean highRisk = confirm(args) || external(args) || "upload".equals(action) || "download".equals(action);
        binding.put("highRisk", highRisk);
        return Map.copyOf(binding);
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
        if (!allowEvaluate && "evaluate".equals(action)) {
            return ToolResult.text("ERROR: 当前浏览器子代理不允许执行 evaluate 任意脚本；请使用 snapshot / inspect / screenshot 等结构化动作。");
        }
        if ("handoff".equals(action)) {
            backend.setAgentControl(sessionId, false, "等待用户接管");
            return handleHandoff(args);
        }
        String targetUrl = targetUrl(args);
        if ((INTERACTIONS.contains(action) || "evaluate".equals(action)) && BrowserCommand.isPaymentUrl(targetUrl)) {
            String blocked = "已拦截支付或结账页面上的自动操作（" + targetUrl
                + "）。请改用 browser(action=\"handoff\", kind=\"payment\", reason=\"请用户在页面上亲自完成支付或确认订单\") 交由用户完成。";
            recordEvidence(action, "blocked", targetUrl, "", blocked, null, Map.of(), "payment");
            return ToolResult.text(blocked);
        }
        // 用户接管：交互与导航被温和拦下（普通提示而非 ERROR，模型理解为等待用户，不会重试风暴）；
        // 观察类放行——用户操作的进度恰是智能体需要的上下文。
        if (backend.interactionsPaused(sessionId) && PAUSED_BLOCKS.contains(action)) {
            return ToolResult.text("用户已接管浏览器并暂停了智能体的页面操作。"
                + "登录、验证码或 MFA 请由用户在当前页面完成；snapshot / screenshot / console / network / wait 等观察动作仍可用。"
                + "用户恢复后先重新 snapshot 验证登录态，不要重放接管前的旧 ref 或交互动作。");
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
            command.put("path", nextDownloadDir().resolve("download-placeholder").toString());
        } else if ("record".equals(action) && "stop".equals(command.get("mode"))) {
            command.put("path", nextRecordingFile(sessionId).toString());
        }
        if ("close".equals(action)) {
            backend.setAgentControl(sessionId, false, "");
        } else if (PAUSED_BLOCKS.contains(action) && !("evaluate".equals(action) && backend.agentControlled(sessionId))) {
            backend.setAgentControl(sessionId, true, controlLabel(action, args));
        }
        BrowserBackend.Reply reply;
        try {
            reply = backend.execute(sessionId, command, timeout(action));
        } catch (BrowserBackend.BrowserException e) {
            String message = e.getMessage() == null ? "浏览器操作失败" : e.getMessage();
            recordEvidence(action, "error", targetUrl, "", message, null, Map.of(), "");
            return ToolResult.text("ERROR: " + message);
        }
        if ("download".equals(action)) {
            prune(nextDownloadDir(), MAX_DOWNLOADS_KEPT);
        } else if ("record".equals(action) && "stop".equals(command.get("mode"))) {
            prune(nextRecordingDir(sessionId), MAX_RECORDINGS_KEPT);
        }
        Map<String, Object> meta = reply.metadata();
        String url = str(meta.get("url")).isEmpty() ? targetUrl : str(meta.get("url"));
        String title = str(meta.get("title"));
        String gateKind = gateKindOf(meta.get("gate"));
        if (reply.screenshot() == null) {
            recordEvidence(action, "ok", url, title, reply.text(), null, meta, gateKind);
            return ToolResult.text(reply.text());
        }
        Path file = Path.of(reply.screenshot());
        String mime =
            file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png") ? "image/png" : "image/jpeg";
        prune(file.getParent(), MAX_SCREENSHOTS_KEPT);
        recordEvidence(action, "ok", url, title, reply.text(), file.toString(), meta, gateKind);
        return ToolResult.withImages(reply.text() + "\n截图文件: " + file,
            List.of(new Attachment(file.getFileName().toString(), mime, file.toString())));
    }

    private ToolResult handleHandoff(Map<String, Object> args) {
        String rawKind = ToolSupport.stringArg(args, "kind");
        String kind =
            rawKind != null && Set.of("auth", "captcha", "payment", "manual").contains(rawKind.toLowerCase(Locale.ROOT))
                ? rawKind.toLowerCase(Locale.ROOT) : "manual";
        String reason = ToolSupport.stringArg(args, "reason");
        if (reason == null) {
            reason = switch (kind) {
                case "auth" -> "需要用户在页面完成登录或 MFA 验证";
                case "captcha" -> "需要用户在页面完成人机验证";
                case "payment" -> "需要用户在页面亲自确认并完成支付";
                default -> "需要用户接管浏览器完成后续操作";
            };
        }
        String currentUrl = targetUrl(args);
        try {
            BrowserBackend.Reply current = backend.execute(sessionId, Map.of("op", "describe"), ACTION_TIMEOUT);
            String describedUrl = str(current.metadata().get("url"));
            if (!describedUrl.isEmpty()) {
                currentUrl = describedUrl;
            }
        } catch (BrowserBackend.BrowserException ignored) {
            // 尚未打开页面时保留已有 URL
        }
        backend.clearOverlay(sessionId);
        if (handoffBroker == null) {
            String note = "已请求用户接管浏览器（" + kind + "：" + reason + "）。请用户在页面完成后告知继续；恢复后先重新 snapshot 验证状态。";
            recordEvidence("handoff", "handoff", currentUrl, "", note, null, Map.of(), kind);
            return ToolResult.text(note);
        }
        BrowserHandoffBroker.PendingHandoff pending =
            new BrowserHandoffBroker.PendingHandoff("hoff_" + UUID.randomUUID().toString().substring(0, 8),
                parentSessionId, sessionId, agentId, kind, reason, currentUrl, Instant.now(), null);
        recordEvidence("handoff", "handoff", currentUrl, "", reason, null, Map.of(), kind);
        try {
            BrowserHandoffBroker.Resolution resolution =
                handoffBroker.request(pending, handoffTimeout).get(handoffTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!resolution.completed()) {
                String cancelled = "已取消浏览器接管" + (resolution.note().isBlank() ? "" : "：" + resolution.note())
                    + "。请向用户确认下一步计划，不要重复盲目发起接管。";
                recordEvidence("handoff", "blocked", currentUrl, "", cancelled, null, Map.of(), kind);
                return ToolResult.text(cancelled);
            }
            backend.invalidateRefs(sessionId);
            String describeText = "";
            String afterUrl = currentUrl;
            String afterTitle = "";
            String afterGate = "";
            try {
                BrowserBackend.Reply after = backend.execute(sessionId, Map.of("op", "describe"), ACTION_TIMEOUT);
                describeText = after.text() == null ? "" : after.text();
                afterUrl = str(after.metadata().get("url")).isEmpty() ? afterUrl : str(after.metadata().get("url"));
                afterTitle = str(after.metadata().get("title"));
                afterGate = gateKindOf(after.metadata().get("gate"));
            } catch (BrowserBackend.BrowserException ignored) {
                // 页面已关闭或尚无活动页
            }
            String resumed = "用户已完成浏览器接管" + (resolution.note().isBlank() ? "" : "（说明：" + resolution.note() + "）")
                + "。旧页面引用（ref）已全部失效，请先重新 snapshot 验证当前页面与登录状态，再继续操作。"
                + (describeText.isBlank() ? "" : "\n" + describeText);
            recordEvidence("handoff", "ok", afterUrl, afterTitle, resumed, null, Map.of(), afterGate);
            return ToolResult.text(resumed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handoffBroker.cancel(sessionId, "任务已中断");
            return ToolResult.text("ERROR: 等待用户完成浏览器接管时任务被中断");
        } catch (TimeoutException e) {
            handoffBroker.cancel(sessionId, "等待超时");
            return ToolResult.text("ERROR: 等待用户完成浏览器接管超时，已结束等待。");
        } catch (ExecutionException e) {
            handoffBroker.cancel(sessionId, "等待异常");
            return ToolResult.text("ERROR: 等待用户接管失败：" + e.getMessage());
        }
    }

    private void recordEvidence(String action, String status, String url, String title, String summary,
        String screenshotPath, Map<String, Object> meta, String gateKind) {
        try {
            String dlPath = "";
            String dlName = "";
            String dlMime = "";
            long dlBytes = 0L;
            if (meta.get("download") instanceof Map<?, ?> dl) {
                dlPath = str(dl.get("path"));
                dlName = str(dl.get("name"));
                dlMime = str(dl.get("mimeType"));
                if (dl.get("sizeBytes") instanceof Number n) {
                    dlBytes = n.longValue();
                }
            }
            List<String> blocked = meta.get("blockedOrigins") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
            int consoleErrors = meta.get("consoleErrors") instanceof Number n ? n.intValue() : 0;
            int failedRequests = meta.get("failedRequests") instanceof Number n ? n.intValue() : 0;
            String shortSummary = summary == null ? "" : summary.strip();
            if (shortSummary.length() > 360) {
                shortSummary = shortSummary.substring(0, 360) + "…";
            }
            String recPath = "";
            if (meta.get("recording") instanceof Map<?, ?> rec) {
                recPath = str(rec.get("path"));
            }
            evidenceRecorder.record(new BrowserEvidenceRecord("ev_" + UUID.randomUUID().toString().substring(0, 12),
                parentSessionId, sessionId, runId, agentId, Instant.now(), action, status, url, title, shortSummary,
                screenshotPath, dlPath, dlName, dlMime, dlBytes, blocked, consoleErrors, failedRequests, gateKind,
                recPath));
        } catch (RuntimeException ignored) {
            // 证据记录异常不阻断工具主执行
        }
    }

    private static String targetUrl(Map<String, Object> args) {
        String expected = ToolSupport.stringArg(args, "expected_url");
        if (expected != null) {
            return expected;
        }
        String url = ToolSupport.stringArg(args, "url");
        return url == null ? "" : url;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static String gateKindOf(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            return str(map.get("kind"));
        }
        return "";
    }

    private static Duration timeout(String action) {
        return switch (action) {
            case "navigate", "new_tab", "reload", "back", "batch" -> NAVIGATION_TIMEOUT;
            case "screenshot", "wait" -> CAPTURE_TIMEOUT;
            default -> ACTION_TIMEOUT;
        };
    }

    private Path nextScreenshot() {
        String bucket = "browser/" + sessionId.replaceAll("[^A-Za-z0-9._-]", "_");
        Path dir = ToolArtifacts.directory(bucket);
        return dir.resolve("shot-" + System.currentTimeMillis() + "-" + shots.incrementAndGet() + ".png");
    }

    private Path nextDownloadDir() {
        String bucket = "browser/" + sessionId.replaceAll("[^A-Za-z0-9._-]", "_") + "/downloads";
        return ToolArtifacts.directory(bucket);
    }

    public static Path nextRecordingDir(String browserSessionId) {
        String clean = browserSessionId == null || browserSessionId.isBlank() ? "default" : browserSessionId;
        String bucket = "browser/" + clean.replaceAll("[^A-Za-z0-9._-]", "_") + "/recordings";
        return ToolArtifacts.directory(bucket);
    }

    public static Path nextRecordingFile(String browserSessionId) {
        return nextRecordingDir(browserSessionId)
            .resolve("rec-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 6)
                + ".replay.json");
    }

    /** 截图与下载目录只留最近若干份，防止长会话把数据目录撑满。 */
    private static void prune(Path dir, int maxKept) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> sorted = files.filter(Files::isRegularFile)
                .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed()).toList();
            for (int i = maxKept; i < sorted.size(); i++) {
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

    private static String controlLabel(String action, Map<String, Object> args) {
        String target = ToolSupport.stringArg(args, "ref");
        if (target == null) {
            target = ToolSupport.stringArg(args, "selector");
        }
        String suffix = target == null ? "" : " " + target;
        return switch (action) {
            case "navigate" -> "打开页面";
            case "new_tab" -> "新建标签";
            case "reload" -> "刷新页面";
            case "back" -> "后退一页";
            case "forward" -> "前进一页";
            case "click", "dblclick", "right_click" -> "点击" + suffix;
            case "hover" -> "查看" + suffix;
            case "fill" -> "填写" + suffix;
            case "type" -> "输入" + suffix;
            case "press" -> {
                String key = ToolSupport.stringArg(args, "key");
                yield key == null ? "按键" : "按键 " + key;
            }
            case "select" -> "选择" + suffix;
            case "check" -> "勾选" + suffix;
            case "uncheck" -> "取消勾选" + suffix;
            case "scroll" -> {
                String dir = ToolSupport.stringArg(args, "direction");
                yield dir == null ? "滚动" + suffix : "滚动 " + dir;
            }
            case "drag" -> "拖拽" + suffix;
            case "dialog" -> "预设弹窗";
            case "batch" -> "批量操作";
            case "record" -> "操作录像";
            case "upload" -> "上传文件" + suffix;
            case "download" -> "下载文件" + suffix;
            case "evaluate" -> "执行页面脚本";
            case "switch_tab" -> "切换标签";
            case "close_tab" -> "关闭标签";
            case "resize" -> "调整视口";
            default -> "正在执行 " + action;
        };
    }
}
