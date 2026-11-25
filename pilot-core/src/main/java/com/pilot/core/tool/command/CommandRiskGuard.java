package com.pilot.core.tool.command;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.pilot.core.util.SecretScrubber;
import com.pilot.core.workspace.PathValidator;

/**
 * {@code AUTO} / 完全放行模式下不可绕过的高危命令硬红线判定。
 *
 * <p>分两档：
 * <ul>
 *   <li>{@link #blockedDelete} / {@link #blockedShellInline}：绝对禁止（删根目录/家目录/系统盘/工作区根与工作区外文件）；</li>
 *   <li>{@link #confirmReason} / {@link #confirmChainReason}：即使在 {@code AUTO} 档位或通用 {@code allow} 规则下也强制二次确认
 *       （强推或远端删除受保护分支、敏感凭证文件或密钥外发）。</li>
 * </ul>
 */
final class CommandRiskGuard {
    private static final Set<String> DELETE_COMMANDS =
        Set.of("rm", "rmdir", "del", "erase", "unlink", "rd", "remove-item", "ri");
    private static final Set<String> SHELL_WRAPPERS =
        Set.of("sh", "bash", "zsh", "dash", "fish", "cmd", "powershell", "pwsh");
    private static final Set<String> PROTECTED_BRANCHES =
        Set.of("main", "master", "dev", "develop", "prod", "production", "release");
    private static final Set<String> GIT_VALUED_GLOBAL_FLAGS =
        Set.of("-c", "-C", "--git-dir", "--work-tree", "--namespace", "--super-prefix", "--config-env");
    private static final Set<String> NETWORK_TOOLS =
        Set.of("curl", "wget", "scp", "sftp", "rsync", "ssh", "nc", "ncat", "socat", "ftp", "http", "xh",
            "invoke-webrequest", "invoke-restmethod", "iwr", "irm", "gh", "aws", "gcloud", "az");
    private static final Set<String> SCRIPT_RUNTIMES =
        Set.of("python", "python3", "node", "ruby", "perl", "php", "powershell", "pwsh", "sh", "bash", "zsh");
    private static final Set<String> SECRET_ENV_NAMES = Set.of("PILOT_MODEL_API_KEY", "OPENAI_API_KEY",
        "ANTHROPIC_API_KEY", "DEEPSEEK_API_KEY", "GEMINI_API_KEY", "AWS_SECRET_ACCESS_KEY", "AWS_ACCESS_KEY_ID",
        "AWS_SESSION_TOKEN", "GITHUB_TOKEN", "GH_TOKEN", "GITLAB_TOKEN", "NPM_TOKEN", "PYPI_TOKEN", "DOCKER_PASSWORD");
    private static final Set<String> UPLOAD_FLAGS = Set.of("--post-file", "--post-data", "--upload-file", "-t", "-f",
        "--form", "--data", "--data-raw", "--data-binary", "--data-urlencode", "-d", "--json", "-infile", "-body");
    private static final Set<String> SENSITIVE_FILENAMES =
        Set.of(".env", ".npmrc", ".pypirc", ".netrc", ".git-credentials", "id_rsa", "id_ed25519", "id_ecdsa", "id_dsa",
            "credentials", "credentials.json", "secrets.json", "secrets.yaml", "secrets.yml", "pilot.db", "kubeconfig");
    private static final Set<String> SAFE_ENV_SUFFIXES = Set.of(".example", ".sample", ".template", ".dist");

    private static final Pattern WINDOWS_DRIVE_ROOT = Pattern.compile("^[a-zA-Z]:([/\\\\]\\*?)?$");
    private static final Pattern HOME_OR_SYSTEM_ENV = Pattern
        .compile("(?i)^(?:~(?:[/\\\\].*)?|\\$(?:\\{?home\\}?|env:(?:home|userprofile|systemdrive|systemroot|windir))"
            + "(?:[/\\\\].*)?|%(?:userprofile|homepath|homedrive|systemdrive|systemroot|windir)%(?:[/\\\\].*)?)$");
    private static final Pattern INLINE_DESTRUCTIVE_DELETE =
        Pattern.compile("(?i)\\b(?:rm|rmdir|del|erase|rd|remove-item|ri)\\b[^;&|\\n]*(?:\\s|/)(?:/\\*?(?:\\s|$|[\"'])|"
            + "~(?:[/\\\\]|\\s|$|[\"'])|\\$\\{?home\\}?|\\$env:(?:home|userprofile)|%(?:userprofile|systemdrive)%|"
            + "[a-z]:[/\\\\]\\*?(?:\\s|$|[\"']))");
    private static final Pattern INLINE_FORCE_PUSH =
        Pattern.compile("(?i)\\bgit\\b[^;&|\\n]*\\bpush\\b[^;&|\\n]*(?:--force(?:-with-lease|-if-includes)?|\\s-f\\b)");
    private static final Pattern INLINE_NETWORK_CALL =
        Pattern.compile("(?i)\\b(?:https?://|fetch\\(|urllib|requests\\.|http\\.request|curl|wget|nc|scp|rsync|"
            + "invoke-webrequest|invoke-restmethod|iwr|irm)\\b");
    private static final Pattern REMOTE_RSYNC_OR_SCP = Pattern
        .compile("^[A-Za-z0-9._-]+@[A-Za-z0-9._-]+:|" + "^(?!localhost:|127\\.0\\.0\\.1:)[A-Za-z0-9._-]{2,}:\\S+");

    private CommandRiskGuard() {
    }

    /** 拦截删根目录/家目录/系统盘/工作区根目录及工作区外路径；允许时返回 null。 */
    static String blockedDelete(List<String> tokens, Path workdir, PathValidator paths) {
        String executable = CommandPolicy.leaf(tokens.getFirst());
        if (!DELETE_COMMANDS.contains(executable)) {
            return null;
        }
        Path home = userHomePath();
        for (int i = 1; i < tokens.size(); i++) {
            String raw = deleteTargetValue(tokens, i);
            if (raw == null) {
                continue;
            }
            String rootProblem = rootOrHomeProblem(raw);
            if (rootProblem != null) {
                return rootProblem;
            }
            String workspaceProblem = resolvedDeleteProblem(raw, workdir, paths, home);
            if (workspaceProblem != null) {
                return workspaceProblem;
            }
        }
        return null;
    }

    /** 拦截通过 sh -c / bash -c / cmd /c / powershell -Command 绕过的根目录/家目录删除。 */
    static String blockedShellInline(List<String> tokens) {
        String script = inlineShellScript(tokens);
        if (script == null) {
            return null;
        }
        if (INLINE_DESTRUCTIVE_DELETE.matcher(script).find()) {
            return "禁止通过嵌套 shell 删除根目录、家目录或工作区外的文件";
        }
        return null;
    }

    /** 单条命令段的不可绕过二次确认原因；无需强制确认时返回 null。 */
    static String confirmReason(List<String> tokens) {
        String gitPushReason = protectedGitPushReason(tokens);
        if (gitPushReason != null) {
            return gitPushReason;
        }
        String exfilReason = credentialExfiltrationReason(tokens);
        if (exfilReason != null) {
            return exfilReason;
        }
        String script = inlineShellScript(tokens);
        if (script != null && INLINE_FORCE_PUSH.matcher(script).find()) {
            return "高危命令需二次确认：嵌套 shell 试图执行强制推送";
        }
        return null;
    }

    /** 管道或串联命令的跨段凭证外发检测（例如 cat .env | nc ... / env | curl ...）。 */
    static String confirmChainReason(List<List<String>> segmentTokens) {
        if (segmentTokens == null || segmentTokens.size() < 2) {
            return null;
        }
        boolean touchesSecret = false;
        boolean sendsNetwork = false;
        for (List<String> tokens : segmentTokens) {
            if (tokens.isEmpty()) {
                continue;
            }
            if (readsOrExposesSecret(tokens)) {
                touchesSecret = true;
            }
            if (canExfiltrateOverNetwork(tokens)) {
                sendsNetwork = true;
            }
        }
        if (touchesSecret && sendsNetwork) {
            return "高危命令需二次确认：检测到管道或串联命令可能外发敏感凭证或密钥文件";
        }
        return null;
    }

    private static String deleteTargetValue(List<String> tokens, int index) {
        String token = tokens.get(index);
        String lower = token.toLowerCase(Locale.ROOT);
        if (lower.startsWith("-path=") || lower.startsWith("-literalpath=")) {
            return token.substring(token.indexOf('=') + 1);
        }
        if (token.startsWith("-")) {
            return null;
        }
        return token;
    }

    private static String rootOrHomeProblem(String token) {
        String cleaned = stripQuotes(token);
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.equals("/") || cleaned.equals("/*") || cleaned.startsWith("//") || cleaned.startsWith("\\\\")
            || WINDOWS_DRIVE_ROOT.matcher(cleaned).matches() || HOME_OR_SYSTEM_ENV.matcher(cleaned).matches()) {
            return "禁止删除根目录、家目录或工作区外的文件：" + token;
        }
        if (cleaned.equals(".") || cleaned.equals("./") || cleaned.equals(".\\")) {
            return "禁止删除工作区根目录：" + token;
        }
        return null;
    }

    private static String resolvedDeleteProblem(String token, Path workdir, PathValidator paths, Path home) {
        String cleaned = stripQuotes(token);
        try {
            Path resolved = paths.resolve(workdir.resolve(cleaned).toString());
            if (resolved.getParent() == null || (home != null && resolved.equals(home))) {
                return "禁止删除根目录、家目录或工作区外的文件：" + token;
            }
            if (resolved.equals(paths.root())) {
                return "禁止删除工作区根目录：" + token;
            }
            return null;
        } catch (RuntimeException e) {
            return "禁止删除工作区外的文件：" + token;
        }
    }

    private static String protectedGitPushReason(List<String> tokens) {
        if (!"git".equals(CommandPolicy.leaf(tokens.getFirst()))) {
            return null;
        }
        int subIndex = gitSubcommandIndex(tokens);
        if (subIndex < 0 || !"push".equals(tokens.get(subIndex))) {
            return null;
        }
        List<String> args = tokens.subList(subIndex + 1, tokens.size());
        boolean force = hasForcePushFlag(args);
        boolean delete = hasDeletePushFlag(args);
        boolean allOrMirror = args.stream().anyMatch(arg -> arg.equals("--all") || arg.equals("--mirror"));
        List<String> positionals = gitPushPositionals(args);
        boolean explicitRepo = args.stream().anyMatch(arg -> arg.equals("--repo") || arg.startsWith("--repo="));
        List<String> refspecs = explicitRepo ? positionals
            : positionals.size() <= 1 ? List.of() : positionals.subList(1, positionals.size());
        if (force && (allOrMirror || refspecs.isEmpty())) {
            return "高危命令需二次确认：未指定非受保护目标分支的 git 强制推送可能覆盖受保护分支";
        }
        for (String refspec : refspecs) {
            if (isForcedOrDeletedProtectedRefspec(refspec, force, delete)) {
                return "高危命令需二次确认：禁止在自动模式下直接强推或删除受保护分支（" + refspec + "）";
            }
        }
        return null;
    }

    static int gitSubcommandIndex(List<String> tokens) {
        int i = 1;
        while (i < tokens.size()) {
            String token = tokens.get(i);
            if (GIT_VALUED_GLOBAL_FLAGS.contains(token)) {
                i += 2;
                continue;
            }
            if (token.startsWith("-")) {
                i++;
                continue;
            }
            return i;
        }
        return -1;
    }

    private static boolean hasForcePushFlag(List<String> args) {
        for (String arg : args) {
            if (arg.equals("-f") || arg.equals("--force") || arg.equals("--force-with-lease")
                || arg.startsWith("--force-with-lease=") || arg.equals("--force-if-includes")) {
                return true;
            }
            if (arg.startsWith("-") && !arg.startsWith("--") && arg.length() > 1 && arg.indexOf('f') > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDeletePushFlag(List<String> args) {
        for (String arg : args) {
            if (arg.equals("-d") || arg.equals("--delete")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> gitPushPositionals(List<String> args) {
        ArrayList<String> result = new ArrayList<>();
        boolean skipNext = false;
        for (String arg : args) {
            if (skipNext) {
                skipNext = false;
                continue;
            }
            if (arg.equals("--repo") || arg.equals("-o") || arg.equals("--push-option") || arg.equals("--receive-pack")
                || arg.equals("--exec")) {
                skipNext = true;
                continue;
            }
            if (arg.startsWith("-")) {
                continue;
            }
            result.add(arg);
        }
        return result;
    }

    private static boolean isForcedOrDeletedProtectedRefspec(String refspec, boolean forceFlag, boolean deleteFlag) {
        boolean forcedRef = forceFlag || refspec.startsWith("+");
        boolean deletedRef = deleteFlag || refspec.startsWith(":");
        if (!forcedRef && !deletedRef) {
            return false;
        }
        String target = refspec.startsWith("+") ? refspec.substring(1) : refspec;
        int colon = target.indexOf(':');
        String branch = colon >= 0 ? target.substring(colon + 1) : target;
        if (branch.startsWith("refs/heads/")) {
            branch = branch.substring("refs/heads/".length());
        }
        if (branch.isEmpty() || branch.equalsIgnoreCase("HEAD")) {
            return forcedRef;
        }
        return isProtectedBranch(branch);
    }

    private static boolean isProtectedBranch(String branch) {
        String lower = branch.toLowerCase(Locale.ROOT);
        return PROTECTED_BRANCHES.contains(lower) || lower.startsWith("release/") || lower.startsWith("hotfix/");
    }

    private static String credentialExfiltrationReason(List<String> tokens) {
        String executable = CommandPolicy.leaf(tokens.getFirst());
        boolean networkTool = NETWORK_TOOLS.contains(executable);
        boolean scriptWithNetwork = SCRIPT_RUNTIMES.contains(executable)
            && tokens.stream().anyMatch(t -> INLINE_NETWORK_CALL.matcher(t).find());
        if (!networkTool && !scriptWithNetwork) {
            return null;
        }
        if (tokens.stream().anyMatch(CommandRiskGuard::referencesSecretOrSensitiveFile)) {
            return "高危命令需二次确认：检测到网络命令可能外发敏感凭证或密钥文件";
        }
        if (isNonLoopbackUpload(executable, tokens)) {
            return "高危命令需二次确认：检测到向外部主机上传或传输本地数据";
        }
        return null;
    }

    private static boolean isNonLoopbackUpload(String executable, List<String> tokens) {
        if (Set.of("scp", "sftp", "ftp").contains(executable)) {
            return true;
        }
        if ("rsync".equals(executable)) {
            return tokens.stream().skip(1).anyMatch(t -> REMOTE_RSYNC_OR_SCP.matcher(t).find());
        }
        if (Set.of("nc", "ncat", "socat").contains(executable)) {
            return tokens.stream().skip(1).anyMatch(t -> !t.startsWith("-") && !isLoopbackToken(t));
        }
        if (Set.of("wget", "invoke-webrequest", "invoke-restmethod", "iwr", "irm").contains(executable)) {
            boolean hasUploadFlag = tokens.stream().skip(1).anyMatch(CommandRiskGuard::isUploadFlag);
            boolean hasRemoteHost = tokens.stream().skip(1).anyMatch(t -> t.contains("://") && !isLoopbackToken(t));
            return hasUploadFlag && hasRemoteHost;
        }
        return false;
    }

    private static boolean isUploadFlag(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        int eq = lower.indexOf('=');
        String key = eq > 0 ? lower.substring(0, eq) : lower;
        return UPLOAD_FLAGS.contains(key);
    }

    private static boolean isLoopbackToken(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return lower.contains("localhost") || lower.contains("127.0.0.1") || lower.contains("[::1]");
    }

    private static boolean readsOrExposesSecret(List<String> tokens) {
        String executable = CommandPolicy.leaf(tokens.getFirst());
        if (Set.of("env", "printenv", "set", "export").contains(executable)) {
            return true;
        }
        return tokens.stream().anyMatch(CommandRiskGuard::referencesSecretOrSensitiveFile);
    }

    private static boolean canExfiltrateOverNetwork(List<String> tokens) {
        String executable = CommandPolicy.leaf(tokens.getFirst());
        if (NETWORK_TOOLS.contains(executable)) {
            return true;
        }
        return SCRIPT_RUNTIMES.contains(executable)
            && tokens.stream().anyMatch(t -> INLINE_NETWORK_CALL.matcher(t).find());
    }

    static boolean referencesSecretOrSensitiveFile(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        if (SecretScrubber.containsSecretPattern(token)) {
            return true;
        }
        String upper = token.toUpperCase(Locale.ROOT);
        for (String envName : SECRET_ENV_NAMES) {
            if (upper.contains(envName)) {
                return true;
            }
        }
        if (upper.contains("AUTHORIZATION:") || upper.contains("X-API-KEY:")) {
            return true;
        }
        return looksLikeSensitivePath(token);
    }

    private static boolean looksLikeSensitivePath(String raw) {
        String cleaned = stripQuotes(raw).replace('\\', '/').toLowerCase(Locale.ROOT);
        if (cleaned.startsWith("@")) {
            cleaned = cleaned.substring(1);
        }
        int eq = cleaned.indexOf('=');
        if (eq > 0 && cleaned.startsWith("-")) {
            cleaned = cleaned.substring(eq + 1);
        }
        if (cleaned.contains("/.ssh/") || cleaned.startsWith(".ssh/") || cleaned.contains("/.aws/credentials")
            || cleaned.endsWith("/.kube/config") || cleaned.equals(".kube/config")) {
            return true;
        }
        String leaf = cleaned.substring(cleaned.lastIndexOf('/') + 1);
        if (SENSITIVE_FILENAMES.contains(leaf)) {
            return true;
        }
        if (leaf.startsWith(".env.")) {
            return SAFE_ENV_SUFFIXES.stream().noneMatch(leaf::endsWith);
        }
        return leaf.endsWith(".pem") || leaf.endsWith(".key") || leaf.endsWith(".p12") || leaf.endsWith(".pfx");
    }

    private static String inlineShellScript(List<String> tokens) {
        if (tokens.size() < 3) {
            return null;
        }
        String executable = CommandPolicy.leaf(tokens.getFirst());
        if (!SHELL_WRAPPERS.contains(executable)) {
            return null;
        }
        for (int i = 1; i < tokens.size() - 1; i++) {
            String flag = tokens.get(i).toLowerCase(Locale.ROOT);
            if (Set.of("-c", "-lc", "/c", "/k", "-command", "-encodedcommand", "-enc").contains(flag)) {
                return tokens.get(i + 1);
            }
        }
        return null;
    }

    private static Path userHomePath() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return null;
        }
        try {
            return Path.of(home).toAbsolutePath().normalize();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String stripQuotes(String value) {
        String s = value == null ? "" : value.strip();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1).strip();
        }
        return s;
    }
}
