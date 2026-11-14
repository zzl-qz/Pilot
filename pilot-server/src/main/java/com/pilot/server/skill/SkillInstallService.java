package com.pilot.server.skill;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.pilot.core.skill.SkillCatalog;
import com.pilot.core.skill.SkillManifest;
import com.pilot.core.skill.SkillProblem;
import com.pilot.core.util.FileTrees;
import com.pilot.server.common.data.PilotDataDir;

/**
 * 技能安装器：把 git 仓库或本机目录里的技能装进项目级/用户级技能目录。
 *
 * <p>安装是<b>纯复制</b>：读、复制并替换两个技能根下的同名目录，不执行来源中的
 * 任何文件，不提供 postinstall 生命周期钩子。技能目录里的脚本以后被模型经
 * run_command 执行时，走的仍是既有白名单与审批链，安装器不新增执行面。
 *
 * <p>一切来源先<b>暂存</b>再解析：GitHub 公共仓库优先下载归档，必要时浅克隆并稀疏检出；
 * 本机目录整体复制到 {@code <dataDir>/tmp/skill-install/<token>/<repoName>/}。这既是
 * 「预览什么就装什么」的保证；完整复制和校验后才切换目标，失败恢复旧版本。
 *
 * <p>复制全程跳过 {@code .git/}：装进技能目录的是纯文件，不是嵌套仓库（代价：不能
 * git pull 更新，更新 = 重新安装）。同名冲突默认拒绝，用户显式覆盖后才替换旧版本。
 */
@Component
public class SkillInstallService {
    private static final Logger log = LoggerFactory.getLogger(SkillInstallService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    /** 暂存目录名：仓库名/来源目录名只能是安全段（防穿越），不必满足技能名规则。 */
    private static final Pattern REPO_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    /** 安装器对用户可读的失败；controller 统一映射 400 带原因。 */
    public static final class InstallException extends RuntimeException {
        public InstallException(String message) {
            super(message);
        }
    }

    /** clone 策略；生产实现 {@link SkillDownloader}，测试注入假实现——测试永不依赖网络。 */
    public interface Cloner {
        void clone(String url, Path targetDir) throws Exception;

        default void clone(String url, Path targetDir, String ref, String path) throws Exception {
            clone(url, targetDir);
        }
    }

    public record Candidate(String name, String description, String command, boolean valid, String reason) {
    }

    public record Preview(String token, String kind, List<Candidate> candidates, String message) {
    }

    public record Outcome(String name, String status, String reason) {
    }

    public record InstallResult(List<Outcome> results) {
    }

    private record Source(boolean git, String value, String ref, String path) {
    }

    private record Staged(Path root, Path scanRoot, long createdAt) {
    }

    private final SkillService skills;
    private final PilotDataDir dataDir;
    private final Cloner cloner;
    private final long tokenTtlMs;
    private final int stageMaxFiles;
    private final long stageMaxBytes;
    private final int skillMaxFiles;
    private final long skillMaxBytes;
    private final ConcurrentHashMap<String, Staged> staged = new ConcurrentHashMap<>();

    @Autowired
    public SkillInstallService(SkillService skills, PilotDataDir dataDir) {
        this(skills, dataDir, new SkillDownloader(new GitCloner()), 10L * 60 * 1000, 2000, 64L * 1024 * 1024, 500,
            20L * 1024 * 1024);
    }

    /** 测试构造：可缩小 TTL 与各上限（与 CommandTaskManager 注入 limits 的做法一致）。 */
    public SkillInstallService(SkillService skills, PilotDataDir dataDir, Cloner cloner, long tokenTtlMs,
        int stageMaxFiles, long stageMaxBytes, int skillMaxFiles, long skillMaxBytes) {
        this.skills = skills;
        this.dataDir = dataDir;
        this.cloner = cloner;
        this.tokenTtlMs = tokenTtlMs;
        this.stageMaxFiles = stageMaxFiles;
        this.stageMaxBytes = stageMaxBytes;
        this.skillMaxFiles = skillMaxFiles;
        this.skillMaxBytes = skillMaxBytes;
    }

    /**
     * 取来源到暂存区并解析候选。支持单技能、标准 skills/ 集合及嵌套分类目录；
     * 候选沿用技能格式校验，独立于运行时目录的数量上限。
     */
    public Preview preview(String source, String workspace) {
        return preview(source, workspace, null, null);
    }

    public Preview preview(String source, String workspace, String ref, String path) {
        requireEnabled();
        sweep();
        Source src = parseSource(source, ref, path);
        String token = newToken();
        Path stageRoot = dataDir.file("tmp", "skill-install", token);
        try {
            Files.createDirectories(stageRoot);
            Path repo = stageRoot.resolve("source").resolve(repoNameOf(src));
            Files.createDirectories(repo.getParent());
            if (src.git()) {
                cloner.clone(src.value(), repo, src.ref(), src.path());
            } else {
                copyTree(Path.of(src.value()), repo, "来源", stageMaxFiles, stageMaxBytes);
            }
            Path selected = src.path().isEmpty() ? repo : repo.resolve(src.path()).normalize();
            if (!selected.startsWith(repo) || !Files.isDirectory(selected, LinkOption.NOFOLLOW_LINKS)) {
                throw new InstallException("来源中没有找到技能目录：" + src.path());
            }
            Path scanRoot = stageCandidates(stageRoot, selected);
            SkillCatalog catalog = SkillCatalog.installationCandidates(scanRoot);
            List<Candidate> candidates = new ArrayList<>();
            for (SkillManifest manifest : catalog.list()) {
                candidates.add(new Candidate(manifest.name(), manifest.description(), manifest.command(), true, null));
            }
            for (SkillProblem problem : catalog.invalid()) {
                candidates.add(new Candidate(problem.name(), null, null, false, problem.reason()));
            }
            String message = candidates.isEmpty() ? "来源里没有找到技能：支持三种布局——根目录直接放 SKILL.md（单技能）、"
                + "每个一级子目录各一个（集合仓）、或 skills/<名字>/SKILL.md 包裹布局（如 anthropics/skills）" : null;
            staged.put(token, new Staged(stageRoot, scanRoot, System.currentTimeMillis()));
            return new Preview(token, src.git() ? "git" : "local", List.copyOf(candidates), message);
        } catch (InstallException e) {
            deleteTreeQuietly(stageRoot);
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deleteTreeQuietly(stageRoot);
            throw new InstallException("技能下载已取消");
        } catch (Exception e) {
            deleteTreeQuietly(stageRoot);
            throw new InstallException("读取来源失败：" + e.getMessage());
        }
    }

    /**
     * 从暂存区把选中技能复制到目标根。逐技能出结果：installed / conflict / invalid / error，
     * 部分成功是常态（A 装上、B 冲突），conflict 是业务状态不是协议错误。
     */
    public synchronized InstallResult install(String token, List<String> names, boolean project, String workspace,
        boolean overwrite) {
        requireEnabled();
        sweep();
        Staged entry = token == null ? null : staged.get(token);
        if (entry == null) {
            throw new InstallException("预览不存在或已过期（10 分钟），请重新查看内容");
        }
        if (names == null || names.isEmpty()) {
            throw new InstallException("请选择要安装的技能");
        }
        SkillCatalog catalog = SkillCatalog.installationCandidates(entry.scanRoot());
        Set<String> valid = catalog.list().stream().map(SkillManifest::name).collect(Collectors.toSet());
        Path root = (project ? skills.projectRootFor(workspace) : skills.userRootOf()).toAbsolutePath().normalize();
        Path rootNormalized = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new InstallException("创建目标目录失败：" + root);
        }
        List<Outcome> results = new ArrayList<>();
        for (String requested : names) {
            String name = requested == null ? "" : requested.strip();
            if (!valid.contains(name)) {
                results.add(new Outcome(name.isEmpty() ? "（空）" : name, "invalid", "该技能在来源中无效或不存在，无法安装"));
                continue;
            }
            // 技能名已被目录校验规则封死穿越；这里再套一层 normalize + startsWith 兜底
            Path from = entry.scanRoot().resolve(name).normalize();
            Path to = root.resolve(name).normalize();
            if (!from.startsWith(entry.scanRoot().normalize()) || !to.startsWith(rootNormalized)) {
                results.add(new Outcome(name, "invalid", "非法的技能目录路径"));
                continue;
            }
            if (Files.isSymbolicLink(to)) {
                results.add(new Outcome(name, "error", "目标是符号链接，请先处理目标目录"));
                continue;
            }
            if (Files.exists(to)) {
                if (!overwrite) {
                    results.add(new Outcome(name, "conflict", "目标目录已存在（同名技能或遗留目录）"));
                    continue;
                }
            }
            try {
                replaceSkill(from, to, root, name);
                results.add(new Outcome(name, "installed", null));
            } catch (IOException e) {
                results.add(new Outcome(name, "error", e.getMessage()));
            }
        }
        // token 只由 TTL 回收：一次预览允许分批安装（先项目级再用户级、冲突后覆盖），
        // 10 分钟后入口统一清扫——覆盖路径天然依赖 token 存活，规则只有一条更好解释
        return new InstallResult(List.copyOf(results));
    }

    /** 清扫过期暂存目录；preview 与 install 的入口都先跑一遍，不起后台定时任务。 */
    synchronized void sweep() {
        long now = System.currentTimeMillis();
        for (var entry : staged.entrySet()) {
            if (now - entry.getValue().createdAt() >= tokenTtlMs) {
                staged.remove(entry.getKey());
                deleteTreeQuietly(entry.getValue().root());
            }
        }
    }

    /** 完整复制和校验成功后才切换目标；失败时恢复旧技能。 */
    private void replaceSkill(Path from, Path to, Path root, String name) throws IOException {
        Path transaction = Files.createTempDirectory(root.getParent(), ".pilot-skill-install-");
        Path next = transaction.resolve(name);
        Path previous = transaction.resolve(".previous");
        boolean retainedBackup = false;
        try {
            copyTree(from, next, "技能", skillMaxFiles, skillMaxBytes);
            if (SkillCatalog.installationCandidates(transaction).find(name).isEmpty()) {
                throw new IOException("复制后的技能校验失败");
            }
            boolean hadPrevious = Files.exists(to, LinkOption.NOFOLLOW_LINKS);
            if (hadPrevious) {
                Files.move(to, previous);
            }
            try {
                Files.move(next, to);
            } catch (IOException failure) {
                if (hadPrevious) {
                    try {
                        Files.move(previous, to);
                    } catch (IOException recovery) {
                        retainedBackup = true;
                        throw new IOException("安装失败；旧版本保留在 " + previous + "，请恢复此目录", recovery);
                    }
                }
                throw failure;
            }
        } finally {
            if (!retainedBackup) {
                deleteTreeQuietly(transaction);
            }
        }
    }

    private void requireEnabled() {
        if (!skills.enabled()) {
            throw new InstallException("技能功能已关闭（pilot.skills.enabled=false）");
        }
    }

    /** 优先标准技能集合，递归发现分类目录；不让仓库里的模板抢占真实技能。 */
    private Path stageCandidates(Path stageRoot, Path repo) throws IOException {
        Path base = repo;
        if (!Files.isRegularFile(repo.resolve("SKILL.md"), LinkOption.NOFOLLOW_LINKS)) {
            for (Path known : List.of(repo.resolve("skills"), repo.resolve(".agents/skills"),
                repo.resolve(".claude/skills"))) {
                if (Files.isDirectory(known, LinkOption.NOFOLLOW_LINKS)) {
                    base = known;
                    break;
                }
            }
        }
        List<Path> found = new ArrayList<>();
        Files.walkFileTree(base, Set.of(), 8, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (Set.of(".git", "node_modules").contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (Files.isRegularFile(dir.resolve("SKILL.md"), LinkOption.NOFOLLOW_LINKS)) {
                    found.add(dir);
                    if (found.size() > 256) {
                        throw new IOException("来源超过 256 个技能，请使用具体技能目录链接");
                    }
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        Path normalized = Files.createDirectories(stageRoot.resolve("candidates"));
        for (Path source : found.stream().sorted().toList()) {
            String name = SkillCatalog.installationName(source);
            if (!REPO_NAME.matcher(name).matches()) {
                throw new InstallException("技能名称不能作为安装目录：" + name);
            }
            Path target = normalized.resolve(name);
            if (Files.exists(target)) {
                throw new InstallException("来源包含多个同名技能 " + name + "，请使用具体技能目录链接");
            }
            copyTree(source, target, "技能候选", stageMaxFiles, stageMaxBytes);
        }
        return normalized;
    }

    private static Source parseSource(String source, String ref, String path) {
        var parsed = SkillInstallSource.parse(source, ref, path);
        return new Source(parsed.git(), parsed.value(), parsed.ref(), parsed.path());
    }

    /** 来源名：URL 末段（去 .git 后缀）或来源目录名；只需是安全段，不要求满足技能名规则。 */
    private static String repoNameOf(Source src) {
        String raw;
        if (src.git()) {
            String trimmed = URI.create(src.value()).getPath();
            while (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            if (trimmed.endsWith(".git")) {
                trimmed = trimmed.substring(0, trimmed.length() - 4);
            }
            int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
            raw = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        } else {
            Path path = Path.of(src.value()).normalize();
            raw = path.getFileName() == null ? "" : path.getFileName().toString();
        }
        if (raw.isEmpty() || raw.equals(".") || raw.equals("..")) {
            return "repository";
        }
        return raw;
    }

    /** 复制目录树，跳过 {@code .git}，超上限即抛错中止（装一半的目录由调用方清理）。 */
    private static void copyTree(Path from, Path to, String what, int maxFiles, long maxBytes) throws IOException {
        var state = new Object() {
            int files = 0;
            long bytes = 0;
        };
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (".git".equals(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path target = to.resolve(from.relativize(dir).toString());
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (".git".equals(file.getFileName().toString())) {
                    return FileVisitResult.CONTINUE;
                }
                if (!attrs.isRegularFile() || attrs.isSymbolicLink()) {
                    throw new IOException(what + "包含符号链接或特殊文件，无法安全导入：" + from.relativize(file));
                }
                state.files += 1;
                state.bytes += attrs.size();
                if (state.files > maxFiles) {
                    throw new IOException(what + "文件数超过上限（" + maxFiles + "），已中止");
                }
                if (state.bytes > maxBytes) {
                    throw new IOException(what + "体积超过上限（" + (maxBytes / 1024 / 1024) + "MB），已中止");
                }
                Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTreeQuietly(Path path) {
        try {
            deleteTree(path);
        } catch (IOException e) {
            log.warn("清理技能安装暂存目录失败：{}", path, e);
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (Files.exists(path)) {
            FileTrees.delete(path);
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        var text = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            text.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return text.toString();
    }

    static final class GitCloner implements Cloner {
        @Override
        public void clone(String url, Path target) throws Exception {
            clone(url, target, null, "");
        }

        @Override
        public void clone(String url, Path target, String ref, String path) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            Path hooks = Files.createDirectories(target.getParent().resolve(".empty-hooks"));
            List<String> command = new ArrayList<>(List.of("clone", "--depth", "1", "--single-branch", "--no-tags",
                "--filter=blob:none", "--no-checkout"));
            boolean commit = ref != null && ref.matches("[a-fA-F0-9]{40,64}");
            if (ref != null && !commit) {
                command.addAll(List.of("--branch", ref));
            }
            command.addAll(List.of("--", url, target.toString()));
            run(command, hooks, target, deadline);
            if (commit) {
                run(List.of("-C", target.toString(), "fetch", "--depth", "1", "origin", ref), hooks, target, deadline);
            }
            if (!path.isEmpty()) {
                run(List.of("-C", target.toString(), "sparse-checkout", "set", "--cone", "--", path), hooks, target,
                    deadline);
            }
            run(List.of("-C", target.toString(), "checkout", "--detach", commit ? "FETCH_HEAD" : "HEAD"), hooks, target,
                deadline);
        }

        private static void run(List<String> arguments, Path hooks, Path target, long deadline) throws Exception {
            List<String> command = new ArrayList<>(List.of("git", "-c", "core.hooksPath=" + hooks));
            command.addAll(arguments);
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            builder.environment().put("GCM_INTERACTIVE", "Never");
            Process process;
            try {
                process = builder.start();
            } catch (IOException failure) {
                throw new InstallException("无法启动 Git，请安装 Git 或使用可直接下载的公共 GitHub 技能链接");
            }
            process.getOutputStream().close();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (var stream = process.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = stream.read(buffer)) != -1) {
                        synchronized (output) {
                            if (output.size() < 4096) {
                                output.write(buffer, 0, Math.min(count, 4096 - output.size()));
                            }
                        }
                    }
                } catch (IOException ignored) {
                }
            });
            try {
                while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                    if (System.nanoTime() >= deadline) {
                        throw new InstallException("Git 下载超时（60 秒），请检查网络或使用本机目录");
                    }
                    checkDiskBudget(target);
                }
                reader.join(1000);
                if (process.exitValue() != 0) {
                    synchronized (output) {
                        throw new InstallException("Git 下载失败：" + output.toString(StandardCharsets.UTF_8).strip());
                    }
                }
                checkDiskBudget(target);
            } finally {
                process.descendants().forEach(handle -> {
                    if (handle.isAlive()) {
                        handle.destroyForcibly();
                    }
                });
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                process.getInputStream().close();
                reader.interrupt();
            }
        }

        private static void checkDiskBudget(Path target) throws IOException {
            if (!Files.exists(target)) {
                return;
            }
            try (var walk = Files.walk(target)) {
                long bytes = 0;
                int files = 0;
                var iterator = walk.iterator();
                while (iterator.hasNext()) {
                    Path file = iterator.next();
                    BasicFileAttributes attrs;
                    try {
                        attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    } catch (NoSuchFileException vanished) {
                        continue;
                    }
                    if (attrs.isRegularFile()) {
                        bytes += attrs.size();
                        if (++files > 10000 || bytes > 128L * 1024 * 1024) {
                            throw new InstallException("Git 暂存超过 10000 文件 / 128MB 上限，请使用具体技能目录链接");
                        }
                    }
                }
            }
        }
    }
}
