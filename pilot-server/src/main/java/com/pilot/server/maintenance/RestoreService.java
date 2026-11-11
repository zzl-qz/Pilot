package com.pilot.server.maintenance;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.LoggerFactory;

import com.pilot.server.store.SqliteStore;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 先验证备份，在目标同卷建立副本，最后通过可回滚的切换发布。 */
final class RestoreService {
    record Options(Path data, Path database, Path attachments, Map<String, Path> workspaces) {
    }

    record Plan(Path backup, Path data, Path database, Path attachments, Path attachmentEntry,
        boolean keepExternalAttachments, JsonNode manifest, RestorePaths paths, WorkspaceRestore workspaces,
        List<String> notices) {
    }

    record Result(Plan plan, Path previous) {
    }

    private final ObjectMapper mapper;

    RestoreService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Plan preflight(Path backup, Options options) throws IOException {
        Path source = backup.toAbsolutePath().normalize();
        JsonNode manifest = BackupManifest.readPublished(source, mapper);
        BackupManifest.Check check = BackupManifest.verify(source, manifest);
        if (!check.valid()) {
            throw new IOException(String.join("；", check.failures()));
        }
        if (!Files.isRegularFile(source.resolve(BackupManifest.DATABASE), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("此备份只含文件，恢复实例需要 SQLite 数据库");
        }
        Path data = DataDirectoryLock.canonical(options.data());
        String originalRoot = manifest.path("dataDir").asText("");
        String oldDatabase = manifest.path("databaseSource").asText("");
        Path database =
            target(options.database(), data, originalRoot, oldDatabase, BackupManifest.DATABASE, "--database");
        String oldExternal = manifest.path("externalAttachmentPath").asText("");
        String oldAttachments = manifest.path("attachmentSource")
            .asText(oldExternal.isEmpty() ? originalRoot + "/attachments" : oldExternal);
        Path attachmentEntry = BackupManifest.relative(manifest.path("attachmentEntry").asText("attachments"));
        boolean keepExternal = !oldExternal.isEmpty() && !manifest.has("attachmentEntry");
        Path attachments =
            target(options.attachments(), data, originalRoot, oldAttachments, "attachments", "--attachments");
        checkTargets(data, database, attachments, source);
        new RestoreSwap(data, mapper).requireReady();
        BackupDatabase.check(source.resolve(BackupManifest.DATABASE),
            keepExternal ? attachments : source.resolve(attachmentEntry), oldAttachments, mapper);

        List<String> notices = new ArrayList<>();
        if (!BackupManifest.VERSION.equals(BackupManifest.version(manifest))) {
            notices.add("旧版备份的工作区范围和一致性信息有限");
        }
        if (keepExternal) {
            notices.add("沿用已核验的外置附件：" + attachments);
        }
        manifest.path("excludedData").forEach(node -> notices.add("清单排除项：" + node.asText()));
        Map<String, Path> mappings = new LinkedHashMap<>();
        mappings.put(originalRoot, data);
        mappings.put(oldAttachments, attachments);
        Map<String, Path> requested = new LinkedHashMap<>();
        for (var entry : options.workspaces().entrySet()) {
            requested.put(entry.getKey(), DataDirectoryLock.canonical(entry.getValue()));
        }
        mappings.putAll(requested);
        WorkspaceRestore workspaces = new WorkspaceRestore(manifest, requested, mapper);
        workspaces.retainExistingLocations(mappings);
        RestorePaths paths = new RestorePaths(mappings, mapper);
        notices.addAll(workspaces.inspect(source, data, database, attachments, paths));
        Plan plan = new Plan(source, data, database, attachments, attachmentEntry, keepExternal, manifest, paths,
            workspaces, List.copyOf(notices));
        checkSpace(plan, data, database, attachments);
        return plan;
    }

    Result restore(Path backup, Options options) throws IOException {
        Plan initial = preflight(backup, options);
        try (var lock = DataDirectoryLock.acquire(initial.data(), initial.database(), initial.attachments())) {
            Plan plan = preflight(backup, options);
            String id = UUID.randomUUID().toString();
            Path control = DataDirectoryLock.control(plan.data());
            Path stage = control.resolve("staging-" + id);
            Path previous = control.resolve("previous-" + id);
            Path stagedData = stage.resolve(plan.data().getFileName());
            boolean databaseOutside = !plan.database().startsWith(plan.data());
            boolean attachmentsOutside = !plan.attachments().startsWith(plan.data());
            Path stagedDatabase = databaseOutside ? stageOutside(plan.database(), id)
                : stagedData.resolve(plan.data().relativize(plan.database()));
            Path stagedAttachments = plan.keepExternalAttachments() ? plan.attachments() : attachmentsOutside
                ? stageOutside(plan.attachments(), id) : stagedData.resolve(plan.data().relativize(plan.attachments()));
            List<Path> staging = new ArrayList<>(List.of(stage));
            if (databaseOutside) {
                staging.add(stagedDatabase.getParent());
            }
            if (attachmentsOutside && !plan.keepExternalAttachments()) {
                staging.add(stagedAttachments.getParent());
            }
            RestoreSwap swap = new RestoreSwap(plan.data(), mapper);
            try {
                checkSpace(plan, stagedData, stagedDatabase, stagedAttachments);
                BackupManifest.privateDirectories(stagedData);
                if (!plan.keepExternalAttachments()) {
                    BackupManifest.privateDirectories(stagedAttachments);
                }
                Path stagedBundles = stage.resolve("bundles");
                copyPayload(plan, stagedData, stagedDatabase, stagedAttachments, stagedBundles);
                copyTree(plan.data().resolve("backups"), stagedData.resolve("backups"));
                migrate(plan, stagedData, stagedDatabase, stagedAttachments);
                List<RestoreSwap.Step> steps = new ArrayList<>();
                Files.createDirectories(previous);
                steps.add(new RestoreSwap.Step(plan.data(), stagedData, previous.resolve(plan.data().getFileName())));
                if (databaseOutside) {
                    Path oldDatabase = previousOutside(plan.database(), id);
                    Files.createDirectories(oldDatabase.getParent());
                    for (String extension : List.of("-wal", "-shm", "-journal")) {
                        steps.add(new RestoreSwap.Step(Path.of(plan.database() + extension), null,
                            Path.of(oldDatabase + extension)));
                    }
                    steps.add(new RestoreSwap.Step(plan.database(), stagedDatabase, oldDatabase));
                }
                if (attachmentsOutside && !plan.keepExternalAttachments()) {
                    Path oldAttachments = previousOutside(plan.attachments(), id);
                    Files.createDirectories(oldAttachments.getParent());
                    steps.add(new RestoreSwap.Step(plan.attachments(), stagedAttachments, oldAttachments));
                }
                RestoreSwap.Check workspaceCheck =
                    plan.workspaces().stage(stagedBundles, plan.data(), stagedData, plan.paths(), id, steps, staging);
                swap.apply(steps, () -> {
                    BackupDatabase.check(plan.database(), plan.attachments(), plan.attachments().toString(), mapper);
                    workspaceCheck.run();
                });
                return new Result(plan, previous);
            } finally {
                // 切换日志尚在时保留所有暂存文件，供下次回滚定位。
                try {
                    swap.requireReady();
                    for (Path path : staging) {
                        if (Files.exists(path)) {
                            BackupManifest.deleteTree(path);
                        }
                    }
                } catch (IOException cleanup) {
                    LoggerFactory.getLogger(RestoreService.class).warn("保留恢复暂存文件", cleanup);
                }
            }
        }
    }

    private void migrate(Plan plan, Path data, Path database, Path attachments) throws IOException {
        SqliteStore sqlite = new SqliteStore(database);
        try (var connection = sqlite.open()) {
            connection.setAutoCommit(false);
            plan.paths().database(connection, plan.manifest().path("defaultWorkspace").asText(""));
            connection.commit();
        } catch (SQLException e) {
            throw new IOException("无法迁移恢复副本：" + e.getMessage(), e);
        }
        sqlite.checkpoint();
        plan.paths().files(data);
        BackupDatabase.check(database, attachments, plan.attachments().toString(), mapper);
    }

    private static Path target(Path explicit, Path data, String originalRoot, String original, String fallback,
        String option) throws IOException {
        if (explicit != null) {
            return DataDirectoryLock.canonical(explicit);
        }
        if (original.isEmpty() || originalRoot.isEmpty()) {
            return data.resolve(fallback);
        }
        String relative = RestorePaths.suffix(original, originalRoot);
        if (relative == null || relative.isEmpty()) {
            throw new IOException("此备份使用外置路径，请显式指定 " + option + "：" + original);
        }
        return data.resolve(BackupManifest.relative(relative));
    }

    private static void checkTargets(Path data, Path database, Path attachments, Path source) throws IOException {
        Path backups = data.resolve("backups");
        if (data.getParent() == null || database.equals(data) || attachments.equals(data)
            || database.startsWith(attachments) || attachments.startsWith(database) || data.startsWith(attachments)
            || data.startsWith(database) || database.startsWith(backups) || attachments.startsWith(backups)
            || source.startsWith(database) || source.startsWith(attachments) || data.startsWith(source)
            || database.startsWith(source) || attachments.startsWith(source)
            || Files.exists(data) && !Files.isDirectory(data)
            || Files.exists(database) && !Files.isRegularFile(database)
            || Files.exists(attachments) && !Files.isDirectory(attachments)) {
            throw new IOException("恢复目标路径重叠或文件类型不符");
        }
    }

    private static Path stageOutside(Path path, String id) throws IOException {
        return DataDirectoryLock.control(path).resolve("staging-" + id).resolve(path.getFileName());
    }

    private static Path previousOutside(Path path, String id) throws IOException {
        return DataDirectoryLock.control(path).resolve("previous-" + id).resolve(path.getFileName());
    }

    private static Path payloadTarget(Plan plan, Path data, Path database, Path attachments, Path relative)
        throws IOException {
        if (relative.equals(Path.of(BackupManifest.DATABASE))) {
            return database;
        }
        if (!plan.keepExternalAttachments() && relative.startsWith(plan.attachmentEntry())) {
            return attachments.resolve(plan.attachmentEntry().relativize(relative));
        }
        if (relative.startsWith("backups") || relative.startsWith(".pilot-maintenance")
            || relative.startsWith("_external")) {
            throw new IOException("备份含有未声明的保留路径：" + relative);
        }
        return data.resolve(relative);
    }

    private static void copyPayload(Plan plan, Path data, Path database, Path attachments, Path bundles)
        throws IOException {
        for (JsonNode item : plan.manifest().path("files")) {
            Path relative = BackupManifest.relative(item.path("path").asText());
            Path target = relative.startsWith(WorkspaceArchive.DIRECTORY) ? bundles.resolve(relative)
                : payloadTarget(plan, data, database, attachments, relative);
            Files.createDirectories(target.getParent());
            if (!BackupManifest.regularFile(plan.backup(), relative)) {
                throw new IOException("备份文件已变化：" + relative);
            }
            Files.copy(plan.backup().resolve(relative), target, LinkOption.NOFOLLOW_LINKS);
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || !BackupManifest.matches(target, item, BackupManifest.version(plan.manifest()))) {
                throw new IOException("恢复暂存文件校验失败：" + relative);
            }
        }
    }

    private static void checkSpace(Plan plan, Path data, Path database, Path attachments) throws IOException {
        Map<FileStore, Long> required = new LinkedHashMap<>();
        for (JsonNode item : plan.manifest().path("files")) {
            Path relative = BackupManifest.relative(item.path("path").asText());
            Path target = payloadTarget(plan, data, database, attachments, relative);
            required.merge(fileStore(target), item.path("bytes").asLong(), Math::addExact);
        }
        Path backups = plan.data().resolve("backups");
        if (Files.isDirectory(backups, LinkOption.NOFOLLOW_LINKS)) {
            try (var files = Files.walk(backups)) {
                for (Path file : files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                    required.merge(fileStore(data), Files.size(file), Math::addExact);
                }
            }
        }
        for (var volume : required.entrySet()) {
            if (volume.getKey().getUsableSpace() < volume.getValue()) {
                throw new IOException("恢复目标卷空间不足：" + volume.getKey());
            }
        }
    }

    private static FileStore fileStore(Path path) throws IOException {
        Path ancestor = path;
        while (!Files.exists(ancestor)) {
            ancestor = ancestor.getParent();
        }
        return Files.getFileStore(ancestor);
    }

    static void copyTree(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var files = Files.walk(from)) {
            for (Path file : files.toList()) {
                Path target = to.resolve(from.relativize(file));
                if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(file, target, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }
}
