package com.pilot.server.maintenance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pilot.server.common.data.PilotDataDir;
import com.pilot.server.workspace.GitWorktrees;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class WorkspaceRestore {
    private final List<WorkspaceArchive.Workspace> workspaces;
    private final Map<String, Path> requested;
    private final ObjectMapper mapper;

    WorkspaceRestore(JsonNode manifest, Map<String, Path> requested, ObjectMapper mapper) throws IOException {
        this.requested = Map.copyOf(requested);
        this.mapper = mapper;
        List<WorkspaceArchive.Workspace> entries = new ArrayList<>();
        for (JsonNode node : manifest.path("workspaces")) {
            var workspace = mapper.treeToValue(node, WorkspaceArchive.Workspace.class);
            if (workspace.path() == null || workspace.path().isBlank()
                || !Set.of("directory", "git", "worktree").contains(workspace.kind())
                || workspace.included() && !workspace.id().matches("(wt_)?[a-f0-9-]{8,36}")) {
                throw new IOException("备份工作区清单无效");
            }
            entries.add(workspace);
        }
        workspaces = List.copyOf(entries);
    }

    List<String> inspect(Path backup, Path data, Path database, Path attachments, RestorePaths paths)
        throws IOException {
        List<String> notices = new ArrayList<>();
        for (var workspace : workspaces) {
            String mapped = paths.relocate(workspace.path());
            if (!workspace.included()) {
                notices.add((directoryExists(mapped) ? "沿用工作区 " : "需另行提供工作区 ") + mapped);
                continue;
            }
            Path archive = workspace.archive(backup);
            requireArchive(archive, workspace);
            if (!requested.containsKey(workspace.path())) {
                notices.add("已打包工作区，指定新路径后可恢复 " + workspace.path());
                continue;
            }
            Path target = requested.get(workspace.path());
            checkTarget(target, data, database, attachments, backup);
            if (workspace.linked()) {
                Path base = requested.get(workspace.base());
                if (base == null) {
                    throw new IOException("恢复 Git worktree 需要同时映射其基础仓库：" + workspace.base());
                }
                checkTarget(base, data, database, attachments, backup);
                if (target.startsWith(base) || base.startsWith(target)) {
                    throw new IOException("Git worktree 与基础仓库的恢复路径重叠");
                }
            }
            notices.add("将恢复工作区 " + workspace.path() + " → " + target);
        }
        return List.copyOf(notices);
    }

    void retainExistingLocations(Map<String, Path> mappings) throws IOException {
        for (var workspace : workspaces) {
            for (String original : List.of(workspace.path(), workspace.base())) {
                if (!original.isBlank() && !requested.containsKey(original) && directoryExists(original)) {
                    Path existing = Path.of(original);
                    if (existing.isAbsolute()) {
                        mappings.putIfAbsent(original, DataDirectoryLock.canonical(existing));
                    }
                }
            }
        }
    }

    private static void requireArchive(Path archive, WorkspaceArchive.Workspace workspace) throws IOException {
        List<String> files =
            workspace.git() ? List.of("tree.json", "history.bundle", "index.patch", "status") : List.of("tree.json");
        for (String file : files) {
            if (!Files.isRegularFile(archive.resolve(file), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("工作区备份文件缺失：" + archive.resolve(file));
            }
        }
    }

    private static void checkTarget(Path target, Path data, Path database, Path attachments, Path source)
        throws IOException {
        if (target.equals(data) || data.startsWith(target) || target.startsWith(data.resolve("backups"))
            || target.startsWith(database) || database.startsWith(target) || target.startsWith(attachments)
            || attachments.startsWith(target) || source.startsWith(target) || target.startsWith(source)
            || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("导入工作区需要独立且尚未使用的目标目录：" + target);
        }
    }

    RestoreSwap.Check stage(Path backup, Path data, Path stagedData, RestorePaths paths, String id,
        List<RestoreSwap.Step> steps, List<Path> temporary) throws IOException {
        Map<Path, Path> stages = new LinkedHashMap<>();
        Map<Path, Path> repair = new LinkedHashMap<>();
        Map<Path, Path> verify = new LinkedHashMap<>();
        for (var workspace : workspaces.stream().filter(WorkspaceArchive.Workspace::included)
            .filter(item -> requested.containsKey(item.path()))
            .sorted(Comparator.comparing(WorkspaceArchive.Workspace::linked)).toList()) {
            Path target = requested.get(workspace.path());
            if (stages.containsKey(target)) {
                throw new IOException("多个工作区不能恢复到同一目录：" + target);
            }
            Path staged = createStage(target, data, stagedData, id, stages, steps, temporary);
            Path archive = workspace.archive(backup);
            if (workspace.linked()) {
                Path base = requested.get(workspace.base());
                Path stagedBase = stages.get(base);
                if (stagedBase == null) {
                    stagedBase = createStage(base, data, stagedData, id, stages, steps, temporary);
                    Files.createDirectories(stagedBase);
                    WorkspaceArchive.gitText(stagedBase, "init", "--template=");
                    fetch(stagedBase, archive, WorkspaceArchive.BASELINE);
                    WorkspaceArchive.gitText(stagedBase, "checkout", "--force", "-B", "pilot-restored",
                        WorkspaceArchive.BASELINE);
                }
                fetch(stagedBase, archive, WorkspaceArchive.HEAD);
                Files.createDirectories(staged.getParent());
                WorkspaceArchive.gitText(stagedBase, "worktree", "add", "--detach", "--no-checkout", staged.toString(),
                    WorkspaceArchive.HEAD);
                repair.put(target, base);
            } else if (workspace.git()) {
                Files.createDirectories(staged);
                WorkspaceArchive.gitText(staged, "init", "--template=");
                fetch(staged, archive, WorkspaceArchive.HEAD);
                if (workspace.branch().equals("HEAD")) {
                    WorkspaceArchive.gitText(staged, "update-ref", "--no-deref", "HEAD", WorkspaceArchive.HEAD);
                } else {
                    WorkspaceArchive.gitText(staged, "branch", "--", workspace.branch(), WorkspaceArchive.HEAD);
                    WorkspaceArchive.gitText(staged, "symbolic-ref", "HEAD", "refs/heads/" + workspace.branch());
                }
            }
            if (workspace.git()) {
                if (!workspace.head().equals(WorkspaceArchive.gitText(staged, "rev-parse", "--verify", "HEAD"))) {
                    throw new IOException("工作区的备份提交与清单不匹配：" + workspace.path());
                }
                WorkspaceArchive.gitText(staged, "read-tree", "HEAD");
                WorkspaceArchive.restoreIndex(archive, staged);
            }
            WorkspaceArchive.materialize(archive, staged, mapper);
            if (workspace.git()) {
                WorkspaceArchive.restoreIntentToAdd(archive, staged);
                WorkspaceArchive.verifyStatus(archive, staged);
                verify.put(target, archive);
            }
        }
        rebind(data, stagedData, paths);
        return () -> {
            for (var item : repair.entrySet()) {
                WorkspaceArchive.gitText(item.getValue(), "worktree", "repair", item.getKey().toString());
            }
            for (var item : verify.entrySet()) {
                WorkspaceArchive.verifyStatus(item.getValue(), item.getKey());
            }
        };
    }

    private static void fetch(Path target, Path archive, String ref) throws IOException {
        WorkspaceArchive.gitText(target, "fetch", "--no-tags", "--", archive.resolve("history.bundle").toString(),
            "+" + ref + ":" + ref);
    }

    private static Path createStage(Path target, Path data, Path stagedData, String id, Map<Path, Path> stages,
        List<RestoreSwap.Step> steps, List<Path> temporary) throws IOException {
        for (Path other : stages.keySet()) {
            if (target.startsWith(other) || other.startsWith(target)) {
                throw new IOException("工作区恢复路径相互重叠：" + target);
            }
        }
        Path staged;
        if (target.startsWith(data)) {
            staged = stagedData.resolve(data.relativize(target));
        } else {
            Path control = DataDirectoryLock.control(target);
            Path folder = Files.createDirectories(control.resolve("staging-" + id));
            staged = folder.resolve(target.getFileName());
            temporary.add(folder);
            // 目标目录为新目录，回滚只需移回本次暂存区。
            steps.add(new RestoreSwap.Step(target.toString(), staged.toString(),
                control.resolve("unused-" + id).toString(), false));
        }
        stages.put(target, staged);
        return staged;
    }

    private void rebind(Path data, Path stagedData, RestorePaths paths) throws IOException {
        GitWorktrees registry = new GitWorktrees(new PilotDataDir(stagedData.toString()));
        for (var meta : registry.registrations()) {
            String newPath = paths.relocate(meta.path());
            String newBase = paths.relocate(meta.base());
            if (directoryExists(newPath)) {
                Path tree = Path.of(newPath).toAbsolutePath().normalize();
                if (tree.startsWith(data.resolve("worktrees"))) {
                    Path staged = stagedData.resolve(data.relativize(tree));
                    if (!Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) {
                        RestoreService.copyTree(tree, staged);
                    }
                }
            }
            registry
                .restoreRegistration(new GitWorktrees.Meta(meta.id(), newPath, newBase, meta.branch(), meta.commit()));
        }
    }

    private static boolean directoryExists(String path) {
        try {
            return Files.isDirectory(Path.of(path));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
