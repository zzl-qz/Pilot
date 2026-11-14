package com.pilot.core.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.pilot.core.runtime.ExecutablePath;
import com.pilot.core.util.FileTrees;
import com.pilot.core.util.HostOs;
import com.pilot.core.workspace.PathValidator;

import tools.jackson.databind.ObjectMapper;

/** 在明确批准后准备工作区私有 Python 环境；固定来源/配方，不接受任意安装命令。 */
public final class PrepareRuntimeTool implements Tool {
    private static final String STATUS_FILE = "status.txt";
    /** 写进状态文件的标记，只给人排查环境问题用，程序不回读。 */
    private static final String STATUS_PREPARING = "PREPARING";
    private static final String STATUS_READY = "READY";
    private static final String STATUS_CLEANED = "CLEANED";
    private static final String STATUS_FAILED_PREFIX = "FAILED: ";
    private static final Map<String, String> PACKAGES =
        Map.of("excel", "openpyxl==3.1.5", "slides", "python-pptx==1.0.2", "documents", "python-docx==1.2.0");
    private final PathValidator paths;
    private final Runner runner;
    private final List<String> python;
    private final boolean windows;

    @FunctionalInterface
    public interface Runner {
        int run(List<String> command, Path workdir, Path log) throws Exception;
    }

    public PrepareRuntimeTool(PathValidator paths) {
        this(paths, HostOs.isWindows());
    }

    private PrepareRuntimeTool(PathValidator paths, boolean windows) {
        this(paths, PrepareRuntimeTool::runProcess, findPython(System.getenv("PATH"), windows), windows);
    }

    public PrepareRuntimeTool(PathValidator paths, Runner runner, List<String> python, boolean windows) {
        this.paths = paths;
        this.runner = runner;
        this.python = List.copyOf(python);
        this.windows = windows;
    }

    @Override
    public String name() {
        return "prepare_runtime";
    }

    @Override
    public String description() {
        return "先 action=plan 查看配方、来源、固定版本、目录和影响；prepare 在用户确认后创建工作区私有 venv 并安装固定依赖，失败清理环境、保留日志。无需 Python 时优先使用内置 office 工具。cleanup 仅删除本工具生成的 env_id，仍需确认。虚拟环境不是 OS 沙箱。";
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public boolean readOnly(Map<String, Object> args) {
        return "plan".equals(args.get("action"));
    }

    @Override
    public boolean confirm(Map<String, Object> args) {
        return args != null && List.of("prepare", "cleanup").contains(String.valueOf(args.get("action")));
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties",
            Map.of("action", Map.of("type", "string", "enum", List.of("plan", "prepare", "cleanup")), "recipe",
                Map.of("type", "string", "enum", List.of("excel", "slides", "documents")), "env_id",
                Map.of("type", "string")),
            "required", List.of("action"), "additionalProperties", false);
    }

    @Override
    public String approvalDescription(Map<String, Object> args) {
        if ("cleanup".equals(args.get("action"))) {
            return "删除 Pilot 生成的任务依赖环境 " + args.get("env_id") + "；保留 manifest 和日志，不删除用户产物。";
        }
        String recipe = String.valueOf(args.get("recipe"));
        return PACKAGES.containsKey(recipe) ? plan(recipe) : "未知环境配方，执行时将拒绝：" + recipe;
    }

    private String plan(String recipe) {
        String dependency = PACKAGES.get(recipe);
        if (dependency == null) {
            throw new IllegalArgumentException("未知配方");
        }
        return "配方=" + recipe + "，依赖=" + dependency + "\n来源=https://pypi.org/simple（仅 wheel，拒绝源码构建）；传递依赖由安装报告记录版本和下载哈希。"
            + "\n执行=" + python + " -m venv --copies；仅写 " + paths.root().resolve(".pilot-task-envs")
            + " 下的新环境，不修改全局 PATH 或系统 Python。需要联网，安装后运行包导入检查。失败删除 venv，保留日志和状态；cleanup 可移除。"
            + (python.isEmpty() ? "\n当前未发现 Python；prepare 不会执行。可使用内置 office 或由用户安装 Python。" : "");
    }

    @Override
    public String execute(Map<String, Object> args) {
        Path bucket = null;
        try {
            String action = ToolSupport.stringArg(args, "action");
            String recipe = ToolSupport.stringArg(args, "recipe");
            if ("plan".equals(action)) {
                return plan(recipe);
            }
            if ("cleanup".equals(action)) {
                String id = ToolSupport.stringArg(args, "env_id");
                if (id == null || !id.matches("env_[a-f0-9-]{36}")) {
                    throw new IllegalArgumentException("无效 env_id");
                }
                bucket = managedBucket(id);
                Path manifest = bucket.resolve("manifest.json");
                if (!Files.isRegularFile(manifest)) {
                    throw new IllegalArgumentException("找不到该环境的准备记录");
                }
                FileTrees.deleteIfExists(bucket.resolve("venv"));
                mark(bucket, STATUS_CLEANED);
                return "已清理 " + id + "，保留准备记录与日志";
            }
            if (!"prepare".equals(action)) {
                throw new IllegalArgumentException("未知 action");
            }
            String details = plan(recipe);
            if (python.isEmpty()) {
                return "ERROR: 未发现 Python，可使用内置 office 工具或请用户安装 Python 后重试";
            }
            String id = "env_" + UUID.randomUUID();
            bucket = managedBucket(id);
            Files.createDirectories(bucket);
            Files.writeString(bucket.resolve("manifest.json"),
                new ObjectMapper().writeValueAsString(Map.of("env_id", id, "recipe", recipe, "plan", details)));
            mark(bucket, STATUS_PREPARING);
            Path environment = bucket.resolve("venv");
            List<String> create = new ArrayList<>(python);
            create.addAll(List.of("-I", "-m", "venv", "--copies", environment.toString()));
            if (runner.run(create, paths.root(), bucket.resolve("prepare.log")) != 0) {
                throw new IOException("venv 创建失败");
            }
            Path executable = environment.resolve(windows ? "Scripts/python.exe" : "bin/python");
            List<String> install = List.of(executable.toString(), "-I", "-m", "pip", "--isolated",
                "--disable-pip-version-check", "install", "--no-input", "--no-cache-dir", "--only-binary=:all:",
                "--index-url", "https://pypi.org/simple", "--report", bucket.resolve("install-report.json").toString(),
                PACKAGES.get(recipe));
            if (runner.run(install, paths.root(), bucket.resolve("install.log")) != 0) {
                throw new IOException("依赖安装失败");
            }
            String module = Map.of("excel", "openpyxl", "slides", "pptx", "documents", "docx").get(recipe);
            if (runner.run(List.of(executable.toString(), "-I", "-c", "import " + module + "; print('IMPORT_OK')"),
                paths.root(), bucket.resolve("verify.log")) != 0) {
                throw new IOException("依赖导入验证失败");
            }
            mark(bucket, STATUS_READY);
            return "环境准备并导入验证通过：env_id=" + id + "\nPython=" + executable + "\n记录=" + paths.display(bucket)
                + "；仅验证环境可用，尚未验证任务产物。";
        } catch (Exception e) {
            if (bucket != null && !"cleanup".equals(args.get("action"))) {
                try {
                    FileTrees.deleteIfExists(bucket.resolve("venv"));
                    mark(bucket, STATUS_FAILED_PREFIX + e.getMessage());
                } catch (Exception cleanup) {
                    return "ERROR: " + e.getMessage() + "；清理未成功：" + cleanup.getMessage() + "；检查 " + bucket;
                }
            }
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "ERROR: 环境准备未完成：" + e.getMessage() + (bucket == null ? "" : "；日志=" + paths.display(bucket));
        }
    }

    private Path managedBucket(String id) {
        Path lexical = paths.root().resolve(".pilot-task-envs").resolve(id).normalize();
        Path resolved = paths.resolve(".pilot-task-envs/" + id);
        if (!lexical.equals(resolved)) {
            throw new IllegalArgumentException("环境目录不能经符号链接重定向");
        }
        return resolved;
    }

    static List<String> findPython(String path, boolean windows) {
        var directories = ExecutablePath.directories(path);
        String primary = windows ? "python.exe" : "python3";
        String fallback = windows ? "py.exe" : "python";
        return ExecutablePath.candidates(directories, primary, fallback).filter(Files::isRegularFile)
            .filter(Files::isExecutable).findFirst()
            .map(candidate -> windows && candidate.getFileName().toString().equals(fallback)
                ? List.of(candidate.toString(), "-3") : List.of(candidate.toString()))
            .orElseGet(List::of);
    }

    private static void mark(Path bucket, String status) throws IOException {
        Files.writeString(bucket.resolve(STATUS_FILE), status);
    }

    private static int runProcess(List<String> command, Path root, Path log) throws Exception {
        ProcessBuilder builder =
            new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().remove("PYTHONPATH");
        builder.environment().remove("PYTHONHOME");
        Process process = builder.start();
        try {
            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                throw new IOException("环境准备命令超时（180 秒）");
            }
            return process.exitValue();
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

}
