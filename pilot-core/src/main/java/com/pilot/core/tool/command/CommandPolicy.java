package com.pilot.core.tool.command;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import com.pilot.core.workspace.PathValidator;

/**
 * 单条命令（一个命令段）的静态判定：能不能跑、是不是只读、会不会写文件。
 *
 * <p>判定只看分词后的参数，不执行任何东西。三档结果：
 * <ul>
 *   <li>拒绝：返回错误文本（不在白名单、危险参数、路径越界）；</li>
 *   <li>只读：权限层在任何模式下都放行，且不需要文件变更追踪；</li>
 *   <li>其余：按权限档位审批。</li>
 * </ul>
 * 文件类工具（ls/cat/rg/mkdir…）的路径参数必须落在工作区内，因为它们不经过 {@code PathValidator}。
 */
final class CommandPolicy {
    /** 只读的查看类工具：不改任何文件。 */
    static final Set<String> INSPECTION = Set.of("ls", "cat", "head", "tail", "wc", "rg", "grep", "find", "tree", "pwd",
        "stat", "file", "du", "diff", "sort", "uniq", "which", "echo");
    /** 会写文件的基础工具：变更由命令前后快照追踪，路径同样限定在工作区内。 */
    static final Set<String> FILE_MUTATORS = Set.of("mkdir", "touch", "cp", "mv");
    /** 构建、测试、运行时与本机服务探测。 */
    static final Set<String> DEVELOPMENT = Set.of("mvn", "mvnw", "gradle", "gradlew", "git", "java", "javac", "node",
        "npm", "npx", "pnpm", "yarn", "bun", "deno", "python", "python3", "pip", "pip3", "uv", "pytest", "ruby",
        "bundle", "php", "composer", "dotnet", "go", "cargo", "rustc", "tsc", "make", "cmake", "ctest", "curl");

    static final Set<String> GIT_READONLY = Set.of("status", "diff", "log", "show", "branch", "rev-parse", "ls-files",
        "describe", "blame", "remote", "shortlog", "ls-tree", "cat-file", "rev-list", "grep");
    static final Set<String> GIT_WRITE = Set.of("add", "commit");
    /** 会改远程、丢掉本地提交或切换检出。允许执行，但任何权限档位都要先弹审批。 */
    static final Set<String> GIT_CONFIRM = Set.of("push", "reset", "clean", "checkout", "switch", "restore", "rebase",
        "merge", "pull", "fetch", "stash", "tag", "cherry-pick", "revert");
    static final Set<String> INSPECTION_FLAGS =
        Set.of("-v", "--version", "-version", "version", "-h", "--help", "help");

    /** 要做路径检查的工具；echo / which / pwd 的参数不是路径。 */
    private static final Set<String> PATH_CHECKED = Set.of("ls", "cat", "head", "tail", "wc", "rg", "grep", "find",
        "tree", "stat", "file", "du", "diff", "sort", "uniq", "mkdir", "touch", "cp", "mv");
    private static final Set<String> FIND_EXECUTING =
        Set.of("-exec", "-execdir", "-ok", "-okdir", "-delete", "-fprint", "-fprint0", "-fprintf", "-fls");
    private static final Set<String> GIT_BRANCH_READ_FLAGS =
        Set.of("-a", "--all", "-r", "--remotes", "-v", "-vv", "--verbose", "--show-current", "--list", "-l",
            "--no-color", "--color", "--merged", "--no-merged", "--contains", "--no-contains", "--sort");
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private CommandPolicy() {
    }

    static boolean knownExecutable(String executable) {
        return INSPECTION.contains(executable) || FILE_MUTATORS.contains(executable)
            || DEVELOPMENT.contains(executable);
    }

    static List<String> allExecutables() {
        return Stream.of(INSPECTION, FILE_MUTATORS, DEVELOPMENT).flatMap(Set::stream).sorted().toList();
    }

    /** 无论什么档位都不允许的动作：发布、全局安装。返回错误文本，允许时返回 null。 */
    static String forbidden(List<String> tokens) {
        String executable = tokens.getFirst();
        List<String> args = tokens.subList(1, tokens.size());
        if (Set.of("npm", "pnpm", "yarn", "bun", "cargo", "composer", "uv", "pip", "pip3").contains(executable)
            && args.stream().anyMatch(arg -> arg.equals("publish"))) {
            return "禁止发布包：publish 需要用户在终端手动执行";
        }
        if (Set.of("npm", "pnpm", "yarn", "bun").contains(executable)
            && args.stream().anyMatch(arg -> arg.equals("-g") || arg.equals("--global") || arg.equals("global"))) {
            return "禁止全局安装或修改全局配置；依赖请安装到工作区内";
        }
        return null;
    }

    /** 发布、全局安装，以及会改远程或丢掉提交的 git。为真时不直接拒绝，交给审批弹窗。 */
    static boolean confirms(List<String> tokens) {
        String executable = tokens.getFirst().toLowerCase(Locale.ROOT);
        if ("git".equals(executable) && tokens.size() > 1
            && GIT_CONFIRM.contains(tokens.get(1).toLowerCase(Locale.ROOT))) {
            return true;
        }
        return forbidden(tokens) != null;
    }

    /** 参数中的危险选项与路径越界。返回错误文本，通过时返回 null。 */
    static String arguments(List<String> tokens, Path workdir, PathValidator paths) {
        String executable = tokens.getFirst();
        switch (executable) {
            case "find" -> {
                for (String token : tokens) {
                    if (FIND_EXECUTING.contains(token)) {
                        return "find 不允许 " + token + "（会执行其他程序或删改文件）；"
                            + "删除请用 apply_patch，批量处理请分步用 glob_files / search_text";
                    }
                }
            }
            case "rg" -> {
                for (String token : tokens) {
                    if (token.equals("--pre") || token.startsWith("--pre=") || token.equals("--hostname-bin")
                        || token.startsWith("--hostname-bin=")) {
                        return "rg 不允许 " + token + "（会执行外部程序）";
                    }
                }
            }
            case "sort" -> {
                for (String token : tokens) {
                    if (token.equals("-o") || token.startsWith("--output") || token.startsWith("--compress-program")) {
                        return "sort 不允许 " + token + "（会写文件或执行程序）";
                    }
                }
            }
            case "tree" -> {
                if (tokens.contains("-o")) {
                    return "tree 不允许 -o（会写文件）";
                }
            }
            case "uniq" -> {
                if (tokens.subList(1, tokens.size()).stream().filter(token -> !token.startsWith("-")).count() > 1) {
                    return "uniq 只接受一个输入文件（第二个位置参数会被当作输出文件写入）";
                }
            }
            case "curl" -> {
                String problem = curl(tokens);
                return problem == null ? null : "curl：" + problem;
            }
            default -> {
            }
        }
        if (!PATH_CHECKED.contains(executable)) {
            return null;
        }
        return pathProblem(tokens, workdir, paths);
    }

    private static String pathProblem(List<String> tokens, Path workdir, PathValidator paths) {
        String executable = tokens.getFirst();
        boolean searchTool = executable.equals("grep") || executable.equals("rg");
        // grep / rg 的第一个位置参数是正则，不是路径；用 -e/-f 给出模式时则全部位置参数都是路径
        boolean explicitPattern = tokens.stream().anyMatch(token -> token.equals("-e") || token.equals("-f")
            || token.startsWith("--regexp") || token.startsWith("--file") || token.equals("--files"));
        boolean patternPending = searchTool && !explicitPattern;
        boolean regexpValuePending = false;
        boolean expression = false;
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (regexpValuePending) {
                regexpValuePending = false;
                continue;
            }
            if (searchTool && (token.equals("-e") || token.equals("--regexp"))) {
                regexpValuePending = true;
                continue;
            }
            if (searchTool && (token.startsWith("--regexp=") || token.startsWith("-e") && token.length() > 2)) {
                continue;
            }
            if (executable.equals("find") && (token.startsWith("-") || token.equals("(") || token.equals("!"))) {
                expression = true;
            }
            if (expression) {
                continue;
            }
            String value = null;
            if (token.startsWith("--") && token.contains("=")) {
                value = token.substring(token.indexOf('=') + 1);
            } else if (token.startsWith("-") && token.length() > 2 && token.charAt(1) != '-'
                && (token.charAt(2) == '/' || token.charAt(2) == '~')) {
                value = token.substring(2);
            } else if (!token.startsWith("-")) {
                if (patternPending) {
                    patternPending = false;
                    continue;
                }
                value = token;
            }
            if (value == null) {
                continue;
            }
            try {
                paths.resolve(workdir.resolve(value).toString());
            } catch (RuntimeException e) {
                return executable + " 的路径参数必须在工作区内：" + value;
            }
        }
        return null;
    }

    /** 命令段是否只读：不改文件、不改仓库状态、不触发网络写入。 */
    static boolean readOnly(List<String> tokens) {
        String executable = tokens.getFirst();
        if (tokens.size() == 2 && INSPECTION_FLAGS.contains(tokens.get(1))) {
            return true;
        }
        if (INSPECTION.contains(executable)) {
            return true;
        }
        if (executable.equals("git")) {
            return tokens.size() > 1 && GIT_READONLY.contains(tokens.get(1)) && gitReadOnly(tokens);
        }
        if (executable.equals("curl")) {
            return curl(tokens) == null && curlReadOnly(tokens);
        }
        return false;
    }

    /** git 的只读子命令里仍有会写东西的参数形态，这里逐个排除。 */
    private static boolean gitReadOnly(List<String> tokens) {
        String sub = tokens.get(1);
        List<String> args = tokens.subList(2, tokens.size());
        for (String arg : args) {
            if (arg.equals("--output") || arg.startsWith("--output=") || arg.equals("-O")
                || arg.startsWith("--open-files-in-pager")) {
                return false;
            }
        }
        return switch (sub) {
            case "branch" -> args.stream()
                .allMatch(arg -> arg.startsWith("-")
                    ? GIT_BRANCH_READ_FLAGS.contains(arg) || arg.startsWith("--sort=") || arg.startsWith("--format=")
                    : args.contains("--list") || args.contains("-l") || args.contains("--contains")
                        || args.contains("--merged") || args.contains("--no-merged"));
            case "remote" -> args.isEmpty() || args.equals(List.of("-v"))
                || (args.size() == 2 && (args.getFirst().equals("show") || args.getFirst().equals("get-url")));
            default -> true;
        };
    }

    // ---- curl：只允许访问本机服务，用来验证开发服务器 ----

    private static final Set<String> CURL_BARE = Set.of("-s", "-S", "-i", "-I", "-v", "-f", "-L", "--silent",
        "--show-error", "--include", "--head", "--verbose", "--fail", "--location", "--compressed", "--fail-with-body");
    private static final Set<String> CURL_VALUED =
        Set.of("-m", "--max-time", "--connect-timeout", "-H", "--header", "-X", "--request", "-w", "--write-out", "-d",
            "--data", "--data-raw", "--data-binary", "--data-urlencode", "--json");

    /** 返回 curl 命令的拒绝原因；符合「只访问本机、不读写本地文件」时返回 null。 */
    static String curl(List<String> tokens) {
        boolean sawUrl = false;
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (CURL_BARE.contains(token)) {
                continue;
            }
            if (token.startsWith("-") && !token.startsWith("--") && token.length() > 2
                && token.chars().skip(1).allMatch(ch -> "sSiIvfL".indexOf(ch) >= 0)) {
                continue;
            }
            if (CURL_VALUED.contains(token)) {
                if (++i >= tokens.size()) {
                    return token + " 缺少参数值";
                }
                String value = tokens.get(i);
                boolean data = token.startsWith("-d") || token.startsWith("--data") || token.equals("--json");
                if (data && value.startsWith("@")) {
                    return "不允许用 @文件 作为请求体，请把内容直接写在参数里";
                }
                continue;
            }
            if (token.startsWith("-")) {
                return "不支持选项 " + token + "（只开放访问本机服务所需的常用选项，不能读写本地文件或使用代理）";
            }
            String host;
            try {
                URI uri = URI.create(token.contains("://") ? token : "http://" + token);
                if (uri.getUserInfo() != null) {
                    return "URL 不能带用户信息";
                }
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                if (!scheme.equals("http") && !scheme.equals("https")) {
                    return "只支持 http/https";
                }
                host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException e) {
                return "URL 格式不合法：" + token;
            }
            if (!LOOPBACK_HOSTS.contains(host)) {
                return "只允许访问本机地址（localhost / 127.0.0.1 / [::1]），外部网页请用 web_fetch：" + token;
            }
            sawUrl = true;
        }
        return sawUrl ? null : "缺少 URL";
    }

    private static boolean curlReadOnly(List<String> tokens) {
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.startsWith("-d") || token.startsWith("--data") || token.equals("--json")) {
                return false;
            }
            if (token.equals("-X") || token.equals("--request")) {
                String method = i + 1 < tokens.size() ? tokens.get(i + 1).toUpperCase(Locale.ROOT) : "";
                if (!method.equals("GET") && !method.equals("HEAD")) {
                    return false;
                }
            }
        }
        return true;
    }
}
