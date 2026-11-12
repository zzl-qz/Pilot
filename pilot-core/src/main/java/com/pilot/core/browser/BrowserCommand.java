package com.pilot.core.browser;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.pilot.core.tool.ToolArtifacts;
import com.pilot.core.tool.ToolSupport;
import com.pilot.core.workspace.PathValidator;

/** 将工具参数转换为浏览器后端的命令。 */
final class BrowserCommand {
    private static final int MAX_UPLOAD_FILES = 10;

    private BrowserCommand() {
    }

    /** 校验并拼装 sidecar 命令；返回非空表示参数错误。 */
    static String build(String action, Map<String, Object> args, Map<String, Object> command, PathValidator paths) {
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
        boolean needsTarget = switch (action) {
            case "hover", "download", "fill", "type", "select", "upload" -> true;
            case "screenshot", "inspect" -> args.get("ref") != null || args.get("selector") != null;
            default -> false;
        };
        if (needsTarget) {
            String problem = target(args, command);
            if (problem != null) {
                return problem;
            }
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
            case "click", "dblclick", "right_click" -> {
                command.put("op", "click");
                if ("dblclick".equals(action)) {
                    command.put("clickCount", 2);
                } else if ("right_click".equals(action)) {
                    command.put("button", "right");
                }
                return target(args, command);
            }
            case "switch_tab", "close_tab", "press", "evaluate" -> {
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
            }
            case "fill", "type" -> {
                Object text = args.get("text");
                if (!(text instanceof String value)) {
                    return action + " 需要 text";
                }
                command.put("text", value);
                copyFlag(args, command, "submit");
            }
            case "select" -> {
                List<String> values = strings(args.get("values"));
                if (values.isEmpty()) {
                    return "select 需要 values";
                }
                command.put("values", values);
            }
            case "upload" -> {
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
                        Path path = uploadable(paths, file);
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
                copyFlag(args, command, "fullPage");
            }
            case "console", "network" -> {
                if ("console".equals(action)) {
                    copy(args, command, "level");
                } else {
                    copy(args, command, "filter");
                    copyFlag(args, command, "failedOnly");
                }
                copyFlag(args, command, "clear");
            }
            case "wait" -> {
                copy(args, command, "text");
                copy(args, command, "selector");
                if (args.get("ms") instanceof Number ms) {
                    command.put("ms", Math.max(0, Math.min(15_000, ms.longValue())));
                }
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
