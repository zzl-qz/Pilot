package com.pilot.server.capability;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

/** Read-only discovery of common local task runtimes. Never launches discovered programs. */
@Service
public class LocalCapabilityService {
    private static final List<CapabilitySpec> SPECS =
        List.of(new CapabilitySpec("java", "Java", List.of("java", "java.exe"), "运行 Pilot 与 Java 工具"),
            new CapabilitySpec("node", "Node.js", List.of("node", "node.exe"), "运行 JavaScript 工具与桌面端组件"),
            new CapabilitySpec("npm", "npm", List.of("npm", "npm.cmd"), "管理 JavaScript 项目依赖"),
            new CapabilitySpec(
                "python", "Python", List.of("python3", "python", "python.exe", "py.exe"), "运行数据处理和文档自动化工具"),
            new CapabilitySpec("git", "Git", List.of("git", "git.exe"), "获取与管理项目和技能版本"),
            new CapabilitySpec("office", "Office / LibreOffice",
                List.of("libreoffice", "soffice", "winword.exe", "EXCEL.EXE", "POWERPNT.EXE"), "打开、转换或检查 Office 文档"),
            new CapabilitySpec("browser", "浏览器",
                List.of("chrome", "chrome.exe", "msedge", "msedge.exe", "firefox", "firefox.exe", "google-chrome"),
                "用于网页任务；Pilot 管理的浏览器另见工具能力状态"),
            new CapabilitySpec("docker", "Docker", List.of("docker", "docker.exe"), "在容器中运行可隔离的任务依赖"));

    public List<LocalCapability> discover() {
        boolean windows = isWindows();
        String pathValue = System.getenv("PATH");
        List<Path> pathEntries = new ArrayList<>();
        if (pathValue != null) {
            for (String entry : pathValue.split(Pattern.quote(File.pathSeparator))) {
                if (entry.isBlank()) {
                    continue;
                }
                try {
                    pathEntries.add(Path.of(entry));
                } catch (InvalidPathException ignored) {
                }
            }
        }
        return SPECS.stream().map(spec -> {
            LocalCapability found = discover(spec, windows, pathEntries);
            if (found.available()) {
                return found;
            }
            for (Path candidate : applicationCandidates(spec.id(), System.getProperty("os.name", ""), System.getenv(),
                Path.of(System.getProperty("user.home", ".")))) {
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return new LocalCapability(spec.id(), spec.label(), true, candidate.toString(),
                        "在系统应用目录发现程序；未启动或验证任务能力。", spec.purpose(), "可按任务需要使用；路径发现不等于能力验收。");
                }
            }
            return found;
        }).toList();
    }

    static List<Path> applicationCandidates(String id, String os, Map<String, String> environment, Path home) {
        List<Path> result = new ArrayList<>();
        String platform = os.toLowerCase(Locale.ROOT);
        if (platform.startsWith("mac") || platform.equals("darwin")) {
            List<String> names = switch (id) {
                case "browser" -> List.of("Google Chrome.app/Contents/MacOS/Google Chrome",
                    "Microsoft Edge.app/Contents/MacOS/Microsoft Edge", "Firefox.app/Contents/MacOS/firefox");
                case "office" -> List.of("LibreOffice.app/Contents/MacOS/soffice",
                    "Microsoft Excel.app/Contents/MacOS/Microsoft Excel",
                    "Microsoft PowerPoint.app/Contents/MacOS/Microsoft PowerPoint",
                    "Microsoft Word.app/Contents/MacOS/Microsoft Word");
                default -> List.of();
            };
            for (Path root : List.of(Path.of("/Applications"), home.resolve("Applications"))) {
                for (String name : names) {
                    result.add(root.resolve(name));
                }
            }
        } else if (platform.startsWith("windows")) {
            List<String> names = switch (id) {
                case "browser" -> List.of("Google/Chrome/Application/chrome.exe",
                    "Microsoft/Edge/Application/msedge.exe", "Mozilla Firefox/firefox.exe");
                case "office" -> List.of("LibreOffice/program/soffice.exe", "Microsoft Office/root/Office16/EXCEL.EXE",
                    "Microsoft Office/root/Office16/WINWORD.EXE", "Microsoft Office/root/Office16/POWERPNT.EXE");
                default -> List.of();
            };
            for (String variable : List.of("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA")) {
                String base = environment.get(variable);
                if (base == null || base.isBlank()) {
                    continue;
                }
                try {
                    for (String name : names) {
                        result.add(Path.of(base).resolve(name));
                    }
                } catch (InvalidPathException ignored) {
                    // 单个安装目录无效不影响其余候选。
                }
            }
        }
        return result;
    }

    static LocalCapability discoverForTest(String id, String name, List<String> commands, List<Path> pathEntries,
        boolean windows) {
        CapabilitySpec spec = new CapabilitySpec(id, name, commands, "测试能力");
        return discover(spec, windows, pathEntries);
    }

    private static LocalCapability discover(CapabilitySpec spec, boolean windows, List<Path> pathEntries) {
        for (String command : spec.commands()) {
            String lowerCommand = command.toLowerCase(Locale.ROOT);
            if (!windows && (lowerCommand.endsWith(".cmd") || lowerCommand.endsWith(".exe"))) {
                continue;
            }
            Optional<Path> match = find(command, pathEntries);
            if (match.isPresent()) {
                return new LocalCapability(spec.id(), spec.label(), true, match.get().toString(),
                    "已发现本机程序；未启动它来读取版本或验证能力。", spec.purpose(), "可按任务需要使用此程序；发现路径不代表已验证任务能力。");
            }
        }
        return new LocalCapability(spec.id(), spec.label(), false, "", "未在 PATH 或受支持的系统应用目录中发现程序；不代表本机未安装",
            spec.purpose(), preparationAdvice(spec.id(), windows));
    }

    private static Optional<Path> find(String command, List<Path> entries) {
        for (Path entry : entries) {
            try {
                Path candidate = entry.resolve(command).toAbsolutePath().normalize();
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return Optional.of(candidate);
                }
            } catch (SecurityException | InvalidPathException ignored) {
                // A malformed PATH entry is not a reason to fail the whole capability report.
            }
        }
        return Optional.empty();
    }

    private static String preparationAdvice(String id, boolean windows) {
        Map<String,
            String> advice = Map.of("java", "Pilot 服务已运行，但此环境未发现 Java 命令；需要本机 Java 工具时可配置 JDK，或选择不依赖 Java 的替代方案。",
                "node", "可先使用 Pilot 自带的 Web 能力；需要 Node 工具时，可在确认来源与版本后安装 Node.js，或用已有语言实现替代。", "npm",
                "若已安装 Node.js，可检查其安装是否包含 npm；依赖应优先安装到任务隔离目录。", "python",
                "可使用现有 Java/Node 能力替代，或在批准后准备隔离的 Python 环境；不建议静默修改系统 Python。", "git",
                "可使用已有文件快照完成简单交付；需要版本管理时可在确认来源后安装 Git。", "office",
                "可直接使用内置 office 创建并检查 xlsx/pptx、渲染幻灯片；其他需求使用已有软件或 prepare_runtime 申请任务依赖。", "browser",
                "可使用 Pilot 已装配的浏览器工具（若能力面板显示可用）；否则可选择文本资料替代或经批准安装受支持浏览器。", "docker",
                "可在任务目录创建隔离虚拟环境替代；容器能力不可用时不要假设任务已获得 OS 级隔离。");
        String platform = windows ? "Windows" : System.getProperty("os.name", "本机");
        return platform + "：" + advice.getOrDefault(id, "检查任务是否可使用已安装工具替代；安装前需用户授权。");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private record CapabilitySpec(String id, String label, List<String> commands, String purpose) {
    }

    public record LocalCapability(String id, String name, boolean available, String executable, String reason,
        String purpose, String preparationAdvice) {
    }
}
