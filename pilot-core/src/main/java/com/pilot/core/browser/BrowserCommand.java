package com.pilot.core.browser;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.pilot.core.tool.ToolArtifacts;
import com.pilot.core.tool.ToolSupport;
import com.pilot.core.workspace.PathValidator;

/** 将工具参数转换为浏览器后端的命令。 */
public final class BrowserCommand {
    private static final int MAX_UPLOAD_FILES = 10;
    static final long MAX_UPLOAD_FILE_BYTES = 25L * 1024L * 1024L;
    static final long MAX_UPLOAD_TOTAL_BYTES = 50L * 1024L * 1024L;
    private static final Set<String> PAYMENT_HOSTS = Set.of("checkout.stripe.com", "paypal.com", "www.paypal.com");
    private static final Set<String> AUTH_HOSTS = Set.of("accounts.google.com", "login.microsoftonline.com",
        "login.live.com", "appleid.apple.com", "idmsa.apple.com");
    private static final Pattern PAYMENT_HOST_PATTERN =
        Pattern.compile("(^|\\.)(?:checkout|payments?)\\.", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAYMENT_PATH_PATTERN =
        Pattern.compile("/(?:checkout|payment|billing)(?:/|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTH_HOST_PATTERN =
        Pattern.compile("(^|\\.)(?:accounts?|auth|identity|login|signin)\\.", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTH_PATH_PATTERN =
        Pattern.compile("/(?:login|log-in|sign-?in|oauth|authorize)(?:/|$)", Pattern.CASE_INSENSITIVE);

    private static final int MAX_BATCH_STEPS = 50;
    private static final Set<String> BATCH_ACTIONS = Set.of("click", "dblclick", "right_click", "hover", "fill", "type",
        "press", "select", "check", "uncheck", "scroll", "drag", "wait", "dialog");

    private BrowserCommand() {
    }

    /** 校验并拼装 sidecar 命令；返回非空表示参数错误。 */
    static String build(String action, Map<String, Object> args, Map<String, Object> command, PathValidator paths) {
        String problem = externalScope(action, args, command);
        if (problem != null) {
            return problem;
        }
        if (needsTarget(action, args)) {
            problem = target(args, command);
            if (problem != null) {
                return problem;
            }
        }
        return dispatch(action, args, command, paths);
    }

    /** 外网动作先要 purpose；交互类还要用 expected_url 绑定审批时的页面。 */
    private static String externalScope(String action, Map<String, Object> args, Map<String, Object> command) {
        if (!Boolean.TRUE.equals(args.get("external"))) {
            return null;
        }
        String purpose = ToolSupport.stringArg(args, "purpose");
        if (purpose == null) {
            return "外网操作需要 purpose 说明目标、最终内容和影响";
        }
        if (!"navigate".equals(action) && !"new_tab".equals(action)
            && ToolSupport.stringArg(args, "expected_url") == null) {
            return "外网交互需要 expected_url 绑定审批时的页面";
        }
        command.put("approvedExternal", true);
        copy(args, command, "expected_url");
        return null;
    }

    /** 必须先有 ref 或 selector 的动作；screenshot / inspect / scroll 只在显式指定目标时才要求。 */
    private static boolean needsTarget(String action, Map<String, Object> args) {
        return switch (action) {
            case "download", "fill", "type", "select", "check", "uncheck", "upload" -> true;
            case "screenshot", "inspect", "scroll" -> args.get("ref") != null || args.get("selector") != null;
            default -> false;
        };
    }

    /** 按动作家族分派到各自的参数校验；返回非空表示参数错误。 */
    private static String dispatch(String action, Map<String, Object> args, Map<String, Object> command,
        PathValidator paths) {
        return switch (action) {
            case "navigate" -> navigate(args, command);
            case "new_tab" -> newTab(args, command);
            case "click", "dblclick", "right_click" -> click(action, args, command);
            case "hover" -> targetOrCoords(args, command, "x", "y");
            case "switch_tab", "close_tab", "press", "evaluate" -> requiredArgument(action, args, command);
            case "fill", "type" -> text(action, args, command);
            case "select" -> select(args, command);
            case "scroll" -> scroll(args, command);
            case "drag" -> drag(args, command);
            case "dialog" -> dialog(args, command);
            case "batch" -> batch(args, command, paths);
            case "record" -> record(args, command);
            case "upload" -> upload(args, command, paths);
            case "resize" -> resize(args, command);
            case "screenshot", "inspect" -> {
                copyFlag(args, command, "fullPage");
                yield null;
            }
            case "console", "network" -> {
                logs(action, args, command);
                yield null;
            }
            case "wait" -> waitFor(args, command);
            // reload / back / snapshot / check / uncheck / close 没有额外参数
            default -> null;
        };
    }

    private static String navigate(Map<String, Object> args, Map<String, Object> command) {
        String url = ToolSupport.stringArg(args, "url");
        if (url == null) {
            return "navigate 需要 url";
        }
        String problem = Boolean.TRUE.equals(args.get("external")) ? checkExternalUrl(url) : checkUrl(url);
        if (problem != null) {
            return problem;
        }
        command.put("url", url);
        return null;
    }

    private static String newTab(Map<String, Object> args, Map<String, Object> command) {
        String url = ToolSupport.stringArg(args, "url");
        if (url == null) {
            return null;
        }
        String problem = Boolean.TRUE.equals(args.get("external")) ? checkExternalUrl(url) : checkUrl(url);
        if (problem != null) {
            return problem;
        }
        command.put("url", url);
        return null;
    }

    /** click / dblclick / right_click 共用 click op，支持 ref/selector 或坐标 (x, y)。 */
    private static String click(String action, Map<String, Object> args, Map<String, Object> command) {
        command.put("op", "click");
        if ("dblclick".equals(action)) {
            command.put("clickCount", 2);
        } else if ("right_click".equals(action)) {
            command.put("button", "right");
        }
        return targetOrCoords(args, command, "x", "y");
    }

    /** switch_tab / close_tab / press / evaluate：一个必填字符串，键名随动作变化。 */
    private static String requiredArgument(String action, Map<String, Object> args, Map<String, Object> command) {
        String key = switch (action) {
            case "press" -> "key";
            case "evaluate" -> "expression";
            default -> "tabId";
        };
        String value = ToolSupport.stringArg(args, key);
        if (value == null) {
            return action + " 需要 " + key;
        }
        command.put(key, value);
        return null;
    }

    /** fill / type：text 必须是字符串，空串照收（用于清空输入框）。 */
    private static String text(String action, Map<String, Object> args, Map<String, Object> command) {
        Object raw = args.get("text");
        if (!(raw instanceof String value)) {
            return action + " 需要 text";
        }
        command.put("text", value);
        copyFlag(args, command, "submit");
        copyFlag(args, command, "clear");
        return null;
    }

    private static String select(Map<String, Object> args, Map<String, Object> command) {
        List<String> values = strings(args.get("values"));
        if (values.isEmpty()) {
            return "select 需要 values";
        }
        command.put("values", values);
        return null;
    }

    private static String scroll(Map<String, Object> args, Map<String, Object> command) {
        String direction = ToolSupport.stringArg(args, "direction");
        if (direction != null) {
            String normalized = direction.toLowerCase(Locale.ROOT);
            if (!Set.of("up", "down", "left", "right").contains(normalized)) {
                return "scroll 的 direction 必须是 up、down、left 或 right";
            }
            command.put("direction", normalized);
        }
        Object rawAmount = args.get("amount");
        if (rawAmount != null) {
            if (!(rawAmount instanceof Number n) || !Double.isFinite(n.doubleValue())
                || n.doubleValue() != Math.rint(n.doubleValue()) || n.intValue() < 1 || n.intValue() > 5000) {
                return "scroll 的 amount 必须是 1–5000 的整数";
            }
            command.put("amount", n.intValue());
        }
        if (args.get("x") != null || args.get("y") != null) {
            return coords(args, command, "x", "y", "scroll");
        }
        copy(args, command, "frame");
        return null;
    }

    private static String drag(Map<String, Object> args, Map<String, Object> command) {
        String problem = targetOrCoords(args, command, "x", "y");
        if (problem != null) {
            return "drag 起点需要 ref/selector 或坐标 (x, y)";
        }
        String targetRef = ToolSupport.stringArg(args, "targetRef");
        String targetSelector = ToolSupport.stringArg(args, "targetSelector");
        if (targetRef != null) {
            command.put("targetRef", targetRef);
            copy(args, command, "frame");
        } else if (targetSelector != null) {
            command.put("targetSelector", targetSelector);
            copy(args, command, "frame");
        } else if (args.get("targetX") != null || args.get("targetY") != null) {
            String dstProblem = coords(args, command, "targetX", "targetY", "drag 终点");
            if (dstProblem != null) {
                return dstProblem;
            }
        } else {
            return "drag 终点需要 targetRef / targetSelector 或坐标 (targetX, targetY)";
        }
        Object rawSteps = args.get("dragSteps");
        if (rawSteps != null) {
            if (!(rawSteps instanceof Number n) || !Double.isFinite(n.doubleValue())
                || n.doubleValue() != Math.rint(n.doubleValue()) || n.intValue() < 2 || n.intValue() > 40) {
                return "drag 的 dragSteps 必须是 2–40 的整数";
            }
            command.put("steps", n.intValue());
        }
        return null;
    }

    private static String dialog(Map<String, Object> args, Map<String, Object> command) {
        Object accept = args.get("accept");
        if (accept != null) {
            if (!(accept instanceof Boolean value)) {
                return "dialog 的 accept 必须是布尔值 true/false";
            }
            command.put("accept", value);
            if (args.get("promptText") instanceof String promptText) {
                command.put("promptText", promptText);
            }
        }
        return null;
    }

    private static String batch(Map<String, Object> args, Map<String, Object> command, PathValidator paths) {
        Object rawSteps = args.get("steps");
        if (!(rawSteps instanceof List<?> list) || list.isEmpty() || list.size() > MAX_BATCH_STEPS) {
            return "batch 的 steps 必须是包含 1–" + MAX_BATCH_STEPS + " 项动作对象的数组";
        }
        List<Map<String, Object>> normalizedSteps = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object rawItem = list.get(i);
            if (!(rawItem instanceof Map<?, ?> rawMap)) {
                return "batch 第 " + (i + 1) + " 步必须是对象";
            }
            Map<String, Object> stepArgs = new LinkedHashMap<>();
            rawMap.forEach((k, v) -> {
                if (k != null) {
                    stepArgs.put(String.valueOf(k), v);
                }
            });
            String subAction = ToolSupport.stringArg(stepArgs, "action");
            if (subAction == null) {
                subAction = ToolSupport.stringArg(stepArgs, "op");
            }
            if (subAction == null) {
                return "batch 第 " + (i + 1) + " 步缺少 action";
            }
            String lower = subAction.toLowerCase(Locale.ROOT);
            if (!BATCH_ACTIONS.contains(lower)) {
                return "batch 第 " + (i + 1) + " 步不支持动作 " + lower;
            }
            if (Boolean.TRUE.equals(stepArgs.get("external"))) {
                return "batch 子步骤不允许单独设置 external";
            }
            Map<String, Object> subCmd = new LinkedHashMap<>();
            subCmd.put("op", lower);
            String stepProblem = dispatch(lower, stepArgs, subCmd, paths);
            if (stepProblem == null && needsTarget(lower, stepArgs)) {
                stepProblem = target(stepArgs, subCmd);
            }
            if (stepProblem != null) {
                return "batch 第 " + (i + 1) + " 步参数错误：" + stepProblem;
            }
            normalizedSteps.add(subCmd);
        }
        command.put("steps", List.copyOf(normalizedSteps));
        if (args.get("stopOnError") instanceof Boolean stopOnError) {
            command.put("stopOnError", stopOnError);
        }
        if (args.get("delayMs") instanceof Number delayMs) {
            command.put("delayMs", Math.max(0, Math.min(1000, delayMs.intValue())));
        }
        return null;
    }

    private static String record(Map<String, Object> args, Map<String, Object> command) {
        String mode = ToolSupport.stringArg(args, "mode");
        if (mode == null) {
            return "record 需要 mode（start / stop / status）";
        }
        String lower = mode.toLowerCase(Locale.ROOT);
        if (!Set.of("start", "stop", "status").contains(lower)) {
            return "record 的 mode 必须是 start、stop 或 status";
        }
        command.put("mode", lower);
        return null;
    }

    /** upload：数量与大小有上限，每个文件都要解析成工作区或产物目录内的普通文件。 */
    private static String upload(Map<String, Object> args, Map<String, Object> command, PathValidator paths) {
        List<String> files = strings(args.get("files"));
        if (files.isEmpty()) {
            return "upload 需要 files";
        }
        if (files.size() > MAX_UPLOAD_FILES) {
            return "upload 一次最多 " + MAX_UPLOAD_FILES + " 个文件";
        }
        List<String> resolved = new ArrayList<>();
        long totalBytes = 0L;
        for (String file : files) {
            try {
                Path path = uploadable(paths, file);
                if (!Files.isRegularFile(path)) {
                    return "文件不存在或不是普通文件: " + file;
                }
                long size = Files.size(path);
                if (size > MAX_UPLOAD_FILE_BYTES) {
                    return "文件超过单文件上传上限（25 MiB）: " + file;
                }
                totalBytes += size;
                if (totalBytes > MAX_UPLOAD_TOTAL_BYTES) {
                    return "上传文件总大小超过上限（50 MiB）";
                }
                resolved.add(path.toString());
            } catch (IOException e) {
                return "无法读取文件大小: " + file;
            } catch (RuntimeException e) {
                return "文件不在工作区内或不可读: " + file;
            }
        }
        command.put("files", resolved);
        return null;
    }

    static boolean hasSensitiveUploadFile(Map<String, Object> args) {
        if (args == null) {
            return false;
        }
        for (String raw : strings(args.get("files"))) {
            String name = Path.of(raw.replace('\\', '/')).getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.equals(".env") || name.startsWith(".env.") || name.equals("id_rsa") || name.equals("id_ed25519")
                || name.equals("id_dsa") || name.equals("id_ecdsa") || name.equals(".npmrc") || name.equals(".netrc")
                || name.equals(".pypirc") || name.endsWith(".pem") || name.endsWith(".key") || name.endsWith(".p12")
                || name.endsWith(".pfx") || name.endsWith(".jks") || name.endsWith(".keystore")
                || name.contains("credentials") || name.contains("secret")) {
                return true;
            }
        }
        return false;
    }

    static List<String> uploadSummaries(Map<String, Object> args, PathValidator paths) {
        if (args == null || paths == null) {
            return List.of();
        }
        List<String> summaries = new ArrayList<>();
        for (String raw : strings(args.get("files"))) {
            try {
                Path resolved = uploadable(paths, raw);
                if (Files.isRegularFile(resolved)) {
                    long bytes = Files.size(resolved);
                    String sizeText =
                        bytes >= 1024 * 1024 ? String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0))
                            : Math.max(1L, Math.round(bytes / 1024.0)) + " KiB";
                    summaries.add(raw + " (" + sizeText + ")");
                    continue;
                }
            } catch (IOException | RuntimeException ignored) {
                // 展示原始路径，避免 approvalDescription 抛异常
            }
            summaries.add(raw);
        }
        return List.copyOf(summaries);
    }

    private static String resize(Map<String, Object> args, Map<String, Object> command) {
        String problem = dimension(args, command, "width", 280, 1920);
        return problem != null ? problem : dimension(args, command, "height", 220, 1600);
    }

    private static String dimension(Map<String, Object> args, Map<String, Object> command, String key, int min,
        int max) {
        if (!(args.get(key) instanceof Number n) || !Double.isFinite(n.doubleValue())
            || n.doubleValue() != Math.rint(n.doubleValue()) || n.doubleValue() < min || n.doubleValue() > max) {
            return "resize 的 " + key + " 必须是 " + min + "–" + max + " 的整数";
        }
        command.put(key, n.intValue());
        return null;
    }

    /** console / network：过滤条件随动作不同，clear 两者通用。 */
    private static void logs(String action, Map<String, Object> args, Map<String, Object> command) {
        if ("console".equals(action)) {
            copy(args, command, "level");
        } else {
            copy(args, command, "filter");
            copyFlag(args, command, "failedOnly");
        }
        copyFlag(args, command, "clear");
    }

    /** wait：ms 收敛到 0–15000，支持 text / selector / url 与 state(visible/hidden)。 */
    private static String waitFor(Map<String, Object> args, Map<String, Object> command) {
        copy(args, command, "text");
        copy(args, command, "selector");
        copy(args, command, "url");
        String state = ToolSupport.stringArg(args, "state");
        if (state != null) {
            String normalized = state.toLowerCase(Locale.ROOT);
            if (!Set.of("visible", "hidden").contains(normalized)) {
                return "wait 的 state 必须是 visible 或 hidden";
            }
            command.put("state", normalized);
        }
        if (args.get("ms") instanceof Number ms) {
            command.put("ms", Math.max(0, Math.min(15_000, ms.longValue())));
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
        copy(args, command, "frame");
        return null;
    }

    private static String targetOrCoords(Map<String, Object> args, Map<String, Object> command, String xKey,
        String yKey) {
        if (args.get("ref") != null || args.get("selector") != null) {
            return target(args, command);
        }
        if (args.get(xKey) != null || args.get(yKey) != null) {
            return coords(args, command, xKey, yKey, "操作");
        }
        return "需要 ref（来自 snapshot）、selector 或坐标 (" + xKey + ", " + yKey + ")";
    }

    private static String coords(Map<String, Object> args, Map<String, Object> command, String xKey, String yKey,
        String prefix) {
        Object rx = args.get(xKey);
        Object ry = args.get(yKey);
        if (!(rx instanceof Number nx) || !(ry instanceof Number ny) || !Double.isFinite(nx.doubleValue())
            || !Double.isFinite(ny.doubleValue())) {
            return prefix + " 需要成对的数字坐标 (" + xKey + ", " + yKey + ")";
        }
        int x = Math.max(0, Math.min(4096, (int)Math.round(nx.doubleValue())));
        int y = Math.max(0, Math.min(4096, (int)Math.round(ny.doubleValue())));
        command.put(xKey, x);
        command.put(yKey, y);
        return null;
    }

    private static void copy(Map<String, Object> args, Map<String, Object> command, String key) {
        String value = ToolSupport.stringArg(args, key);
        if (value != null) {
            command.put(key, value);
        }
    }

    private static void copyFlag(Map<String, Object> args, Map<String, Object> command, String key) {
        if (Boolean.TRUE.equals(args.get(key))) {
            command.put(key, true);
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
    private static Path uploadable(PathValidator paths, String raw) {
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

    /** 返回规范化 HTTP(S) origin（如 http://localhost:5173 或 https://example.com）；无效或非 HTTP(S) 返回 null。 */
    public static String originOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(raw.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return null;
            }
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (host.isEmpty()) {
                return null;
            }
            int port = uri.getPort();
            boolean defaultPort =
                port < 0 || ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
            return scheme + "://" + host + (defaultPort ? "" : ":" + port);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    public static boolean isLoopbackHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalized = host.strip().toLowerCase(Locale.ROOT);
        return normalized.equals("localhost") || normalized.endsWith(".localhost") || normalized.equals("127.0.0.1")
            || normalized.equals("[::1]") || normalized.equals("::1") || normalized.matches("127\\.\\d+\\.\\d+\\.\\d+");
    }

    public static boolean isPaymentUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(raw.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return false;
            }
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            return PAYMENT_HOSTS.contains(host) || PAYMENT_HOST_PATTERN.matcher(host).find()
                || PAYMENT_PATH_PATTERN.matcher(path).find();
        } catch (URISyntaxException e) {
            return false;
        }
    }

    public static boolean isAuthUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(raw.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return false;
            }
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            return AUTH_HOSTS.contains(host) || AUTH_HOST_PATTERN.matcher(host).find()
                || AUTH_PATH_PATTERN.matcher(path).find();
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static String checkUrl(String url) {
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

    private static String checkExternalUrl(String value) {
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
}
