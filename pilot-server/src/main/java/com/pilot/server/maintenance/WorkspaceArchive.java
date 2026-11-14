package com.pilot.server.maintenance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.pilot.server.common.data.PilotDataDir;
import com.pilot.server.git.GitWorktrees;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class WorkspaceArchive {
    static final String DIRECTORY = "_workspaces";
    static final String HEAD = "refs/heads/pilot-head";
    static final String BASELINE = "refs/heads/pilot-baseline";
    private static final String[] STATUS = {"status", "--porcelain=v1", "--no-renames", "--untracked-files=all", "-z"};
    private static final String[] INDEX_DIFF =
        {"diff", "--cached", "--binary", "--full-index", "--no-color", "--no-ext-diff", "--no-textconv", "HEAD", "--"};

    record Workspace(String path, String kind, String id, String base, String branch, String baseline, String head,
        boolean included) {
        boolean linked() {
            return kind.equals("worktree");
        }

        boolean git() {
            return linked() || kind.equals("git");
        }

        Path archive(Path backup) throws IOException {
            return backup.resolve(DIRECTORY).resolve(BackupManifest.relative(id));
        }
    }

    private record FileItem(String path, boolean directory, String link, boolean executable) {
    }

    private WorkspaceArchive() {
    }

    static List<Workspace> capture(PilotDataDir data, Path staging, Set<String> paths, List<Path> selections,
        ObjectMapper mapper) throws IOException {
        Map<String, Workspace> entries = new LinkedHashMap<>();
        for (String path : paths) {
            entries.put(path, new Workspace(path, "directory", "", "", "", "", "", false));
        }
        List<GitWorktrees.Meta> registrations = new GitWorktrees(data).registrations();
        for (var meta : registrations) {
            entries.put(meta.path(), new Workspace(meta.path(), "worktree", meta.id(), meta.base(), meta.branch(),
                meta.commit(), "", false));
        }
        Set<Path> seen = new HashSet<>();
        Path dataRoot = DataDirectoryLock.canonical(data.root());
        for (Path selection : selections) {
            Path root = DataDirectoryLock.canonical(selection);
            Path worktrees = dataRoot.resolve("worktrees");
            if (!Files.isDirectory(root) || root.getParent() == null
                || root.startsWith(dataRoot) && (!root.startsWith(worktrees) || root.equals(worktrees))) {
                throw new IOException("不能导出此工作区目录：" + selection);
            }
            if (!seen.add(root)) {
                continue;
            }
            Workspace workspace = null;
            for (Workspace known : entries.values()) {
                if (DataDirectoryLock.canonical(Path.of(known.path())).equals(root)) {
                    if (workspace == null || known.linked()) {
                        workspace = known;
                    }
                }
            }
            String stored = workspace == null ? selection.toAbsolutePath().normalize().toString() : workspace.path();
            if (workspace == null || !workspace.linked()) {
                workspace = new Workspace(stored,
                    Files.exists(root.resolve(".git"), LinkOption.NOFOLLOW_LINKS) ? "git" : "directory",
                    UUID.randomUUID().toString(), "", "", "", "", true);
            }
            entries.put(stored, export(root, dataRoot, staging, workspace, mapper));
        }
        return List.copyOf(entries.values());
    }

    private static Workspace export(Path root, Path dataRoot, Path staging, Workspace entry, ObjectMapper mapper)
        throws IOException {
        Path archive = Files.createDirectories(entry.archive(staging));
        Workspace exported = entry;
        if (entry.git()) {
            String head = gitText(root, "rev-parse", "--verify", "HEAD");
            String branch = entry.linked() ? entry.branch() : gitText(root, "rev-parse", "--abbrev-ref", "HEAD");
            String baseline = entry.linked() ? entry.baseline() : head;
            exported =
                new Workspace(entry.path(), entry.kind(), entry.id(), entry.base(), branch, baseline, head, true);
            if (gitBytes(root, "ls-files", "--unmerged", "-z").length > 0
                || gitText(root, "ls-files", "--stage").lines().anyMatch(line -> line.startsWith("160000 "))) {
                throw new IOException("工作区含有合并冲突或子模块，请先处理再导出：" + root);
            }
            Files.write(archive.resolve("status"), gitBytes(root, STATUS));
            git(root, archive.resolve("index.patch"), INDEX_DIFF);
            bundle(root, archive, exported);
        }
        Map<Path, FileItem> files = inventory(root, dataRoot, entry.git());
        Path payload = Files.createDirectory(archive.resolve("tree"));
        for (var file : files.entrySet()) {
            FileItem item = file.getValue();
            if (!item.directory() && item.link().isEmpty()) {
                Path destination = payload.resolve(BackupManifest.relative(item.path()));
                Files.createDirectories(destination.getParent());
                Files.copy(file.getKey(), destination, LinkOption.NOFOLLOW_LINKS);
            }
        }
        Files.writeString(archive.resolve("tree.json"), mapper.writeValueAsString(List.copyOf(files.values())));
        if (!files.equals(inventory(root, dataRoot, entry.git()))) {
            throw new IOException("导出期间工作区文件列表或类型发生变化：" + root);
        }
        for (var file : files.entrySet()) {
            FileItem item = file.getValue();
            if (!item.directory() && item.link().isEmpty()
                && Files.mismatch(file.getKey(), payload.resolve(BackupManifest.relative(item.path()))) != -1) {
                throw new IOException("导出期间工作区文件发生变化：" + file.getKey());
            }
        }
        if (entry.git() && (!exported.head().equals(gitText(root, "rev-parse", "--verify", "HEAD"))
            || !Arrays.equals(gitBytes(root, STATUS), Files.readAllBytes(archive.resolve("status")))
            || !sameIndex(root, archive.resolve("index.patch")))) {
            throw new IOException("导出期间 Git 状态发生变化：" + root);
        }
        return exported;
    }

    private static boolean sameIndex(Path root, Path snapshot) throws IOException {
        Path check = Files.createTempFile("pilot-git-index-", ".patch");
        try {
            git(root, check, INDEX_DIFF);
            return Files.mismatch(snapshot, check) == -1;
        } finally {
            Files.deleteIfExists(check);
        }
    }

    private static void bundle(Path root, Path archive, Workspace workspace) throws IOException {
        Path scratch = Files.createTempDirectory(archive.getParent(), ".git-bundle-");
        try {
            gitText(scratch, "init", "--bare", "--template=");
            gitText(scratch, "fetch", "--no-tags", "--", root.toString(), workspace.head() + ":" + HEAD);
            Path base = workspace.linked() ? Path.of(workspace.base()) : root;
            gitText(scratch, "fetch", "--no-tags", "--", base.toString(), workspace.baseline() + ":" + BASELINE);
            gitText(scratch, "bundle", "create", archive.resolve("history.bundle").toString(), HEAD, BASELINE);
        } finally {
            BackupManifest.deleteTree(scratch);
        }
    }

    private static Map<Path, FileItem> inventory(Path root, Path data, boolean git) throws IOException {
        Map<Path, FileItem> result = new LinkedHashMap<>();
        if (git) {
            String listed = new String(gitBytes(root, "ls-files", "-z", "--cached", "--others", "--exclude-standard"),
                StandardCharsets.UTF_8);
            for (String name : listed.split("\0")) {
                if (!name.isEmpty()) {
                    Path path = root.resolve(BackupManifest.relative(name));
                    if (!path.startsWith(data) || root.startsWith(data)) {
                        add(root, result, path);
                    }
                }
            }
        } else {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (dir.equals(data) || dir.getFileName().toString().equals(".pilot-maintenance")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!dir.equals(root)) {
                        add(root, result, dir);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    add(root, result, file);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return result;
    }

    private static void add(Path root, Map<Path, FileItem> result, Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return; // Git 记录了仍在索引中、工作树已删除的文件。
        }
        Path relative = root.relativize(path);
        validateWorkspacePath(relative);
        for (Path parent = path.getParent(); !parent.equals(root); parent = parent.getParent()) {
            if (Files.isSymbolicLink(parent)) {
                throw new IOException("工作区路径的父目录包含符号链接：" + path);
            }
        }
        String link = "";
        boolean directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
        boolean executable = false;
        if (Files.isSymbolicLink(path)) {
            Path target = Files.readSymbolicLink(path);
            checkLink(root, path, target);
            link = BackupManifest.portable(target);
        } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            executable = Files.getFileAttributeView(path, PosixFileAttributeView.class) != null
                && Files.getPosixFilePermissions(path).contains(PosixFilePermission.OWNER_EXECUTE);
        } else if (!directory) {
            throw new IOException("工作区包含非常规文件：" + path);
        }
        result.put(path, new FileItem(BackupManifest.portable(relative), directory, link, executable));
    }

    static void materialize(Path archive, Path target, ObjectMapper mapper) throws IOException {
        JsonNode tree = mapper.readTree(Files.readString(archive.resolve("tree.json")));
        if (!tree.isArray()) {
            throw new IOException("工作区文件清单无效");
        }
        Files.createDirectories(target);
        // 先创建常规文件，最后才创建符号链接。
        List<FileItem> links = new ArrayList<>();
        for (JsonNode node : tree) {
            FileItem item = mapper.treeToValue(node, FileItem.class);
            Path relative = BackupManifest.relative(item.path());
            validateWorkspacePath(relative);
            Path file = target.resolve(relative);
            if (!item.link().isEmpty()) {
                links.add(item);
            } else if (item.directory()) {
                Files.createDirectories(file);
            } else {
                Files.createDirectories(file.getParent());
                if (!BackupManifest.regularFile(archive.resolve("tree"), relative)) {
                    throw new IOException("缺少工作区文件：" + relative);
                }
                Files.copy(archive.resolve("tree").resolve(relative), file);
                if (item.executable() && Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
                    var permissions = Files.getPosixFilePermissions(file);
                    permissions.add(PosixFilePermission.OWNER_EXECUTE);
                    Files.setPosixFilePermissions(file, permissions);
                }
            }
        }
        for (FileItem item : links) {
            Path file = target.resolve(BackupManifest.relative(item.path()));
            Path link = Path.of(item.link());
            checkLink(target, file, link);
            for (Path parent = file.getParent(); !parent.equals(target); parent = parent.getParent()) {
                if (Files.isSymbolicLink(parent)) {
                    throw new IOException("工作区链接的父目录是符号链接：" + file);
                }
            }
            Files.createDirectories(file.getParent());
            try {
                Files.createSymbolicLink(file, link);
            } catch (IOException | UnsupportedOperationException e) {
                throw new IOException("目标文件系统无法建立工作区符号链接：" + file, e);
            }
        }
    }

    private static void validateWorkspacePath(Path relative) throws IOException {
        BackupManifest.relative(BackupManifest.portable(relative));
        for (Path part : relative) {
            if (part.toString().equalsIgnoreCase(".git") || part.toString().equals(".pilot-maintenance")) {
                throw new IOException("工作区包含嵌套 Git 元数据或维护目录：" + relative);
            }
        }
    }

    private static void checkLink(Path root, Path file, Path target) throws IOException {
        Path resolved = file.getParent().resolve(target).normalize();
        if (target.isAbsolute() || !resolved.startsWith(root)) {
            throw new IOException("工作区符号链接指向选定目录之外：" + file);
        }
        if (!resolved.equals(root)) {
            validateWorkspacePath(root.relativize(resolved));
        }
    }

    static void restoreIndex(Path archive, Path target) throws IOException {
        Path index = archive.resolve("index.patch");
        if (Files.size(index) > 0) {
            gitText(target, "apply", "--cached", "--binary", "--", index.toString());
        }
    }

    static void verifyStatus(Path archive, Path target) throws IOException {
        if (!Arrays.equals(Files.readAllBytes(archive.resolve("status")), gitBytes(target, STATUS))) {
            throw new IOException("恢复后的 Git 状态与备份不一致：" + target);
        }
    }

    static void restoreIntentToAdd(Path archive, Path target) throws IOException {
        for (String entry : Files.readString(archive.resolve("status")).split("\0")) {
            if (entry.startsWith(" A ")) {
                String name = entry.substring(3);
                validateWorkspacePath(BackupManifest.relative(name));
                gitText(target, "add", "--intent-to-add", "--", name);
            }
        }
    }

    static String gitText(Path root, String... args) throws IOException {
        return new String(gitBytes(root, args), StandardCharsets.UTF_8).strip();
    }

    private static byte[] gitBytes(Path root, String... args) throws IOException {
        Path output = Files.createTempFile("pilot-git-", ".out");
        try {
            git(root, output, args);
            return Files.readAllBytes(output);
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private static void git(Path root, Path output, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of("git", "--no-optional-locks", "-C", root.toString(), "-c",
            "core.autocrlf=false", "-c", "core.quotepath=false", "-c", "gc.auto=0"));
        command.addAll(List.of(args));
        Path errors = Files.createTempFile("pilot-git-", ".err");
        try {
            command.addAll(4,
                List.of("-c", "core.hooksPath=" + errors.resolveSibling(errors.getFileName() + "-hooks")));
            ProcessBuilder builder =
                new ProcessBuilder(command).redirectOutput(output.toFile()).redirectError(errors.toFile());
            builder.environment().put("GIT_LFS_SKIP_SMUDGE", "1");
            for (String variable : List.of("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_COMMON_DIR")) {
                builder.environment().remove(variable);
            }
            Process child = builder.start();
            try {
                if (!child.waitFor(120, TimeUnit.SECONDS)) {
                    child.destroyForcibly();
                    throw new IOException("Git 操作超时：" + args[0]);
                }
            } catch (InterruptedException e) {
                child.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("Git 操作中断", e);
            }
            if (child.exitValue() != 0) {
                throw new IOException("Git " + args[0] + " 失败：" + Files.readString(errors).strip());
            }
        } finally {
            Files.deleteIfExists(errors);
        }
    }
}
