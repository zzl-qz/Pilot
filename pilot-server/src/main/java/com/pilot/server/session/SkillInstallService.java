package com.pilot.server.session;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.pilot.core.skill.SkillCatalog;
import com.pilot.core.skill.SkillManifest;
import com.pilot.core.skill.SkillProblem;
import com.pilot.core.skill.SkillSource;
import com.pilot.server.config.PilotDataDir;

/**
 * 技能安装器：把 git 仓库或本机目录里的技能装进项目级/用户级技能目录。
 *
 * <p>安装是<b>纯复制</b>：读、复制、（覆盖时）删除两个技能根下的同名目录，不执行来源中的
 * 任何文件——没有 postinstall 生命周期钩子，也永远不做。技能目录里的脚本以后被模型经
 * run_command 执行时，走的仍是既有白名单与审批链，安装器不新增执行面。
 *
 * <p>一切来源先<b>暂存</b>再解析：git 用 {@code --depth 1 --single-branch} clone，
 * 本机目录整体复制到 {@code <dataDir>/tmp/skill-install/<token>/<repoName>/}。这既是
 * 「预览什么就装什么」的原子性保证，也是覆盖安装的安全前提——来源在目标根里时，
 * 原地安装会先删掉自己的来源。
 *
 * <p>复制全程跳过 {@code .git/}：装进技能目录的是纯文件，不是嵌套仓库（代价：不能
 * git pull 更新，更新 = 重新安装）。同名冲突默认拒绝，用户显式覆盖后才删旧装新。
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

    /** clone 策略；生产实现 {@link GitCloner}，测试注入假实现——测试永不依赖网络。 */
    public interface Cloner {
        void clone(String url, Path targetDir) throws Exception;
    }

    public record Candidate(String name, String description, String command, boolean valid, String reason) {
    }

    public record Preview(String token, String kind, List<Candidate> candidates, String message) {
    }

    public record Outcome(String name, String status, String reason) {
    }

    public record InstallResult(List<Outcome> results) {
    }

    private record Source(boolean git, String value) {
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
        this(skills, dataDir, new GitCloner(), 10L * 60 * 1000, 2000, 64L * 1024 * 1024, 500, 20L * 1024 * 1024);
    }

    /** 测试构造：可缩小 TTL 与各上限（与 CommandTaskManager 注入 limits 的做法一致）。 */
    SkillInstallService(SkillService skills, PilotDataDir dataDir, Cloner cloner, long tokenTtlMs, int stageMaxFiles,
        long stageMaxBytes, int skillMaxFiles, long skillMaxBytes) {
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
     * 取来源到暂存区并解析候选。单技能仓（根目录有 SKILL.md）与一级集合仓（每个一级
     * 子目录一个技能）两种布局；候选的有效性沿用 {@link SkillCatalog} 全部校验，零新规则。
     */
    public Preview preview(String source, String workspace) {
        requireEnabled();
        sweep();
        Source src = parseSource(source);
        String token = newToken();
        Path stageRoot = dataDir.file("tmp", "skill-install", token);
        try {
            Files.createDirectories(stageRoot);
            Path repo = stageRoot.resolve(repoNameOf(src));
            if (src.git()) {
                cloner.clone(src.value(), repo);
            } else {
                copyTree(Path.of(src.value()), repo, "来源", stageMaxFiles, stageMaxBytes);
            }
            // 布局判定：根有 SKILL.md → 整个仓库是单个技能（扫描根 = stageRoot，repo 目录即技能名）；
            // 否则按一级子目录解析（扫描根 = repo）。两种布局不混判，根优先。
            Path scanRoot = Files.isRegularFile(repo.resolve("SKILL.md")) ? stageRoot : repo;
            SkillCatalog catalog = SkillCatalog.scan(List.of(new SkillCatalog.Root(scanRoot, SkillSource.PROJECT)));
            List<Candidate> candidates = new ArrayList<>();
            for (SkillManifest manifest : catalog.list()) {
                candidates.add(new Candidate(manifest.name(), manifest.description(), manifest.command(), true, null));
            }
            for (SkillProblem problem : catalog.invalid()) {
                candidates.add(new Candidate(problem.name(), null, null, false, problem.reason()));
            }
            String message = candidates.isEmpty() ? "来源里没有找到技能：单技能仓库在根目录放 SKILL.md，集合仓库的每个一级子目录各放一个" : null;
            staged.put(token, new Staged(stageRoot, scanRoot, System.currentTimeMillis()));
            return new Preview(token, src.git() ? "git" : "local", List.copyOf(candidates), message);
        } catch (InstallException e) {
            deleteTreeQuietly(stageRoot);
            throw e;
        } catch (Exception e) {
            deleteTreeQuietly(stageRoot);
            throw new InstallException("读取来源失败：" + e.getMessage());
        }
    }

    /**
     * 从暂存区把选中技能复制到目标根。逐技能出结果：installed / conflict / invalid / error，
     * 部分成功是常态（A 装上、B 冲突），conflict 是业务状态不是协议错误。
     */
    public InstallResult install(String token, List<String> names, boolean project, String workspace,
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
        SkillCatalog catalog = SkillCatalog.scan(List.of(new SkillCatalog.Root(entry.scanRoot(), SkillSource.PROJECT)));
        Set<String> valid = catalog.list().stream().map(SkillManifest::name).collect(Collectors.toSet());
        Path root = project ? skills.projectRootFor(workspace) : skills.userRootOf();
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
            if (Files.exists(to)) {
                if (!overwrite) {
                    results.add(new Outcome(name, "conflict", "目标目录已存在（同名技能或遗留目录）"));
                    continue;
                }
                try {
                    deleteTree(to);
                } catch (IOException e) {
                    results.add(new Outcome(name, "error", "删除旧目录失败：" + e.getMessage()));
                    continue;
                }
            }
            try {
                copyTree(from, to, "技能", skillMaxFiles, skillMaxBytes);
                results.add(new Outcome(name, "installed", null));
            } catch (IOException e) {
                deleteTreeQuietly(to); // 装一半的目录不留残骸
                results.add(new Outcome(name, "error", e.getMessage()));
            }
        }
        // token 只由 TTL 回收：一次预览允许分批安装（先项目级再用户级、冲突后覆盖），
        // 10 分钟后入口统一清扫——覆盖路径天然依赖 token 存活，规则只有一条更好解释
        return new InstallResult(List.copyOf(results));
    }

    /** 清扫过期暂存目录；preview 与 install 的入口都先跑一遍，不起后台定时任务。 */
    void sweep() {
        long now = System.currentTimeMillis();
        for (var entry : staged.entrySet()) {
            if (now - entry.getValue().createdAt() >= tokenTtlMs) {
                staged.remove(entry.getKey());
                deleteTreeQuietly(entry.getValue().root());
            }
        }
    }

    private void requireEnabled() {
        if (!skills.enabled()) {
            throw new InstallException("技能功能已关闭（pilot.skills.enabled=false）");
        }
    }

    /** 来源解析：http(s) → git；绝对路径 → 本机目录；其余（裸域名、相对路径、~）判非法。 */
    private static Source parseSource(String source) {
        String text = source == null ? "" : source.strip();
        if (text.isEmpty()) {
            throw new InstallException("来源不能为空：填 git 仓库地址（http(s):// 开头）或本机技能目录的绝对路径");
        }
        if (text.startsWith("http://") || text.startsWith("https://")) {
            try {
                var url = URI.create(text).toURL();
                if (url.getHost() == null || url.getHost().isBlank()) {
                    throw new IllegalArgumentException("no host");
                }
            } catch (Exception e) {
                throw new InstallException("git 地址不合法：" + text);
            }
            return new Source(true, text);
        }
        Path path;
        try {
            path = Path.of(text);
        } catch (InvalidPathException e) {
            throw new InstallException("来源既不是 http(s) 开头的 git 地址，也不是合法路径：" + text);
        }
        if (!path.isAbsolute()) {
            throw new InstallException("本机目录要用绝对路径；git 地址必须以 http:// 或 https:// 开头");
        }
        return new Source(false, text);
    }

    /** 来源名：URL 末段（去 .git 后缀）或来源目录名；只需是安全段，不要求满足技能名规则。 */
    private static String repoNameOf(Source src) {
        String raw;
        if (src.git()) {
            String trimmed = src.value();
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
        if (raw.isEmpty() || !REPO_NAME.matcher(raw).matches()) {
            throw new InstallException(
                "来源名不合法（只能是字母数字开头的字母/数字/点/横线/下划线，且不超过 64 字符）：" + (raw.isEmpty() ? src.value() : raw));
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
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
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

    /**
     * 生产环境的 git clone：浅克隆、只拉默认分支、不带 tags，60 秒看门狗。
     * 骨架与 {@code ChangeService.exec} 相同（看门狗超时杀进程，主线程限量收输出），
     * 但那一个绑定工作区语义且吞错，这里要的是完整的失败原因。
     */
    static final class GitCloner implements Cloner {
        private static final int TIMEOUT_SECONDS = 60;
        private static final int MAX_OUTPUT_BYTES = 4096;

        @Override
        public void clone(String url, Path targetDir) throws Exception {
            ProcessBuilder builder = new ProcessBuilder("git", "clone", "--depth", "1", "--single-branch", "--no-tags",
                url, targetDir.toString());
            builder.redirectErrorStream(true);
            Process process;
            try {
                process = builder.start();
            } catch (IOException e) {
                throw new SkillInstallService.InstallException(
                    e.getMessage() != null && e.getMessage().contains("Cannot run program") ? "未找到 git，请安装它或改用本机目录导入"
                        : "git clone 启动失败：" + e.getMessage());
            }
            process.getOutputStream().close();
            var timedOut = new AtomicBoolean(false);
            Thread watchdog = Thread.ofVirtual().name("pilot-skill-clone-watchdog").start(() -> {
                try {
                    if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        timedOut.set(true);
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            var collected = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            try (var stream = process.getInputStream()) {
                int read;
                while ((read = stream.read(buffer)) > 0) {
                    if (collected.size() < MAX_OUTPUT_BYTES) {
                        collected.write(buffer, 0, Math.min(read, MAX_OUTPUT_BYTES - collected.size()));
                    }
                }
            }
            watchdog.interrupt();
            process.waitFor(1, TimeUnit.SECONDS);
            if (timedOut.get()) {
                throw new SkillInstallService.InstallException("git clone 超时（" + TIMEOUT_SECONDS + " 秒）");
            }
            int exit = process.exitValue();
            if (exit != 0) {
                String detail = collected.toString(StandardCharsets.UTF_8).strip();
                throw new SkillInstallService.InstallException(
                    "git clone 失败（退出码 " + exit + "）：" + (detail.isEmpty() ? url : detail));
            }
        }
    }
}
