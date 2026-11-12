package com.pilot.server.session;

import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** 把技能网页链接解析为仓库、版本和目录；不把网页本身交给 git clone。 */
record SkillInstallSource(boolean git, String value, String ref, String path) {
    static SkillInstallSource parse(String input, String requestedRef, String requestedPath) {
        String text = input == null ? "" : input.strip();
        String ref = requestedRef == null || requestedRef.isBlank() ? null : requestedRef.strip();
        String path = requestedPath == null ? "" : requestedPath.strip();
        if (text.isEmpty()) {
            throw failure("来源不能为空，请提供技能链接或本机目录");
        }
        if (!text.toLowerCase(Locale.ROOT).startsWith("http://")
            && !text.toLowerCase(Locale.ROOT).startsWith("https://")) {
            Path local;
            try {
                local = Path.of(text);
            } catch (RuntimeException e) {
                throw failure("本机路径不合法");
            }
            if (!local.isAbsolute()) {
                throw failure("本机目录要用绝对路径；git 地址必须以 http:// 或 https:// 开头");
            }
            if (ref != null) {
                throw failure("本机目录不使用 ref");
            }
            return new SkillInstallSource(false, local.toString(), null, safePath(path));
        }
        URI uri;
        try {
            uri = URI.create(text);
        } catch (IllegalArgumentException e) {
            throw failure("技能链接不合法");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null) {
            throw failure("技能链接必须有主机名且不能内嵌凭据");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        List<String> parts = Arrays.stream(uri.getPath().split("/")).filter(s -> !s.isEmpty()).toList();
        if (List.of("github.com", "gitee.com", "raw.githubusercontent.com").contains(host)) {
            if (parts.size() < 2) {
                throw failure("技能链接缺少仓库所有者或仓库名");
            }
            String owner = parts.get(0);
            String repo = parts.get(1);
            if (!owner.matches("[A-Za-z0-9_.-]+") || !repo.matches("[A-Za-z0-9_.-]+")) {
                throw failure("仓库名称不合法");
            }
            int start = -1;
            if (host.equals("raw.githubusercontent.com")) {
                start = 2;
            } else if (parts.size() > 2 && List.of("tree", "blob", "raw").contains(parts.get(2))) {
                start = 3;
            } else if (parts.size() > 2) {
                throw failure("请提供仓库、技能目录或 SKILL.md 链接");
            }
            if (start >= 0) {
                if (parts.size() <= start) {
                    throw failure("链接缺少分支或提交版本");
                }
                String tail = String.join("/", parts.subList(start, parts.size()));
                String urlRef = ref == null ? parts.get(start) : ref;
                if (!tail.equals(urlRef) && !tail.startsWith(urlRef + "/")) {
                    throw failure("链接中的版本与 ref 不一致；带斜杠分支请显式提供完整 ref");
                }
                if (requestedPath == null || requestedPath.isBlank()) {
                    path = tail.equals(urlRef) ? "" : tail.substring(urlRef.length() + 1);
                }
                ref = urlRef;
            }
            if (path.endsWith("/SKILL.md")) {
                path = path.substring(0, path.length() - 9);
            } else if (path.equals("SKILL.md")) {
                path = "";
            } else if (path.endsWith(".md")) {
                throw failure("文件链接必须指向 SKILL.md，安装会保留整个技能目录");
            }
            String remoteHost = host.equals("raw.githubusercontent.com") ? "github.com" : host;
            text = uri.getScheme() + "://" + remoteHost + "/" + owner + "/" + repo;
        } else if (uri.getPath().matches(".*(/tree/|/blob/|/raw/).*|.*\\.md/?$")) {
            throw failure("此托管站的网页链接尚不支持，请提供 git 仓库地址并指定 path/ref");
        }
        if (ref != null && (!ref.matches("[\\p{L}\\p{N}][\\p{L}\\p{N}._/-]{0,255}") || ref.contains(".."))) {
            throw failure("ref 不是有效的分支、标签或提交标识");
        }
        return new SkillInstallSource(true, text, ref, safePath(path));
    }

    private static String safePath(String path) {
        if (path.isEmpty()) {
            return "";
        }
        if (path.startsWith("/") || path.contains("\\") || path.contains(":") || path.indexOf('\0') >= 0
            || Arrays.stream(path.split("/", -1)).anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals(".."))) {
            throw failure("技能 path 必须是仓库内的相对目录，不能包含穿越路径");
        }
        return path;
    }

    private static SkillInstallService.InstallException failure(String message) {
        return new SkillInstallService.InstallException(message);
    }
}
