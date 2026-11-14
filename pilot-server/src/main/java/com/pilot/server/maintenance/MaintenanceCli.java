package com.pilot.server.maintenance;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;

import com.pilot.server.common.data.PilotDataDir;
import com.pilot.server.config.AttachmentLimits;
import com.pilot.server.session.AttachmentService;
import com.pilot.server.store.SqliteStore;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 维护入口在 Spring 和后台任务启动之前运行。 */
public final class MaintenanceCli {
    private static final Set<String> OPTIONS =
        Set.of("data-dir", "backup", "database", "attachments", "workspace-map", "apply");

    private MaintenanceCli() {
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args.length == 0 || List.of("help", "--help").contains(args[0])) {
                out.println("maintenance backup|verify|restore|rollback --data-dir=目录 [--backup=备份目录或ID]");
                out.println("restore 默认预检，使用 --apply 执行。外置路径用 --database=文件、--attachments=目录。");
                out.println("备份可重复指定 --workspace=目录，恢复时使用 --workspace-map=JSON文件。");
                return 0;
            }
            List<Path> workspaces = new ArrayList<>();
            Map<String, String> flags = parse(Arrays.copyOfRange(args, 1, args.length), workspaces);
            if (!workspaces.isEmpty() && !args[0].equals("backup")) {
                throw new IllegalArgumentException("--workspace 只用于备份");
            }
            Path data = new PilotDataDir(flags.getOrDefault("data-dir", System.getenv("PILOT_DATA_DIR"))).root();
            Path database = path(flags.get("database"));
            Path attachments = path(flags.getOrDefault("attachments", System.getenv("PILOT_ATTACHMENTS_DIR")));
            ObjectMapper mapper = JsonMapper.builder().build();
            switch (args[0]) {
                case "backup" -> backup(data, database, attachments, workspaces, mapper, out);
                case "verify", "restore" -> {
                    Path source = backupPath(data, flags.get("backup"));
                    RestoreService restore = new RestoreService(mapper);
                    var options = new RestoreService.Options(data, database, attachments,
                        workspaceMap(flags.get("workspace-map"), mapper));
                    if (args[0].equals("restore") && flags.containsKey("apply")) {
                        var result = restore.restore(source, options);
                        describe(out, result.plan());
                        out.println("恢复完成。原数据保存在 " + result.previous());
                    } else {
                        describe(out, restore.preflight(source, options));
                        out.println("恢复预检通过");
                    }
                }
                case "rollback" -> {
                    try (var lock = DataDirectoryLock.acquire(data, database, attachments)) {
                        boolean recovered = new RestoreSwap(data, mapper).rollback();
                        out.println(recovered ? "已回滚上次未完成的恢复" : "没有需要回滚的恢复操作");
                    }
                }
                default -> throw new IllegalArgumentException("未知维护命令：" + args[0]);
            }
            return 0;
        } catch (IOException | RuntimeException e) {
            err.println("维护操作失败：" + e.getMessage());
            return 1;
        }
    }

    private static void backup(Path data, Path database, Path attachments, List<Path> workspaces, ObjectMapper mapper,
        PrintStream out) throws IOException {
        Path db = database == null ? data.resolve(BackupManifest.DATABASE) : database;
        Path input = attachments == null ? data.resolve("attachments") : attachments;
        try (var lock = DataDirectoryLock.acquire(data, db, input)) {
            new RestoreSwap(data, mapper).requireReady();
            PilotDataDir directory = new PilotDataDir(data.toString());
            var beans = new DefaultListableBeanFactory();
            beans.registerSingleton("store", SqliteStore.existing(db));
            var service = new BackupService(beans.getBeanProvider(SqliteStore.class), directory, mapper,
                new AttachmentService(input.toString(), directory, AttachmentLimits.defaults()));
            String defaultWorkspace = System.getenv("PILOT_WORKSPACE");
            if (defaultWorkspace != null && !defaultWorkspace.isBlank()) {
                service.useDefaultWorkspace(defaultWorkspace);
            }
            var result = service.backup(workspaces);
            if (!result.ok()) {
                throw new IOException(result.message());
            }
            out.println(result.message());
            out.println(result.directory());
            result.workspaces()
                .forEach(workspace -> out.println((workspace.included() ? "已打包 " : "外部依赖 ") + workspace.path()));
        }
    }

    private static void describe(PrintStream out, RestoreService.Plan plan) {
        out.println("数据目录 " + plan.data());
        out.println("SQLite " + plan.database());
        out.println("附件目录 " + plan.attachments());
        plan.notices().forEach(out::println);
    }

    private static Path backupPath(Path data, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("请选择 --backup=备份目录或ID");
        }
        Path path = Path.of(value);
        return value.matches("[0-9]{8}-[0-9]{6}(?:-[0-9A-Za-z-]+)?") ? data.resolve("backups").resolve(path) : path;
    }

    private static Map<String, Path> workspaceMap(String file, ObjectMapper mapper) throws IOException {
        if (file == null) {
            return Map.of();
        }
        var json = mapper.readTree(Path.of(file).toFile());
        if (json == null || !json.isObject()) {
            throw new IOException("工作区映射必须是原路径到新路径的 JSON 对象");
        }
        Map<String, Path> result = new LinkedHashMap<>();
        for (String key : json.propertyNames()) {
            String target = json.get(key).asText("");
            if (key.isBlank() || target.isBlank() || !Path.of(target).isAbsolute()) {
                throw new IOException("工作区映射需要原路径和绝对目标路径");
            }
            result.put(key, Path.of(target));
        }
        return result;
    }

    private static Path path(String value) {
        return value == null || value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize();
    }

    private static Map<String, String> parse(String[] args, List<Path> workspaces) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String argument : args) {
            if (argument.startsWith("--workspace=")) {
                Path workspace = path(argument.substring("--workspace=".length()));
                if (workspace == null) {
                    throw new IllegalArgumentException("--workspace 需要一个目录");
                }
                workspaces.add(workspace);
                continue;
            }
            if (argument.equals("--apply")) {
                values.put("apply", "true");
                continue;
            }
            int equals = argument.indexOf('=');
            if (!argument.startsWith("--") || equals <= 2 || !OPTIONS.contains(argument.substring(2, equals))) {
                throw new IllegalArgumentException("无效维护选项：" + argument);
            }
            String key = argument.substring(2, equals);
            if (key.equals("apply") || argument.substring(equals + 1).isBlank()
                || values.putIfAbsent(key, argument.substring(equals + 1)) != null) {
                throw new IllegalArgumentException("无效或重复的维护选项：" + key);
            }
        }
        return values;
    }

    /** 只在正常入口启动时获取锁，且在服务 Bean 都销毁后释放。 */
    public static void lockServer(ConfigurableApplicationContext context) {
        var environment = context.getEnvironment();
        Path root = new PilotDataDir(environment.getProperty("pilot.data.dir", "")).root();
        Path database = path(environment.getProperty("pilot.store.path"));
        Path attachments = path(environment.getProperty("pilot.attachments.dir"));
        try {
            DataDirectoryLock lock = DataDirectoryLock.acquire(root, database, attachments);
            try {
                new RestoreSwap(root, JsonMapper.builder().build()).requireReady();
            } catch (IOException | RuntimeException e) {
                lock.close();
                throw e;
            }
            DefaultListableBeanFactory factory = (DefaultListableBeanFactory)context.getBeanFactory();
            factory.registerSingleton("pilotDataDirectoryLock", lock);
            factory.registerDisposableBean("pilotDataDirectoryLock", lock::close);
        } catch (IOException e) {
            throw new UncheckedIOException(e.getMessage(), e);
        }
    }
}
