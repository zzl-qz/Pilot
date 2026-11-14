package com.pilot.server;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 守住 pilot-server 的包依赖方向，防止包之间重新缠在一起。
 * 做法很朴素：读主代码源文件，看每个包引用了哪些别的包，然后检查几条规则。
 * 不引入额外的架构测试库。
 */
class PackageDependencyTest {
    private static final String BASE = "com.pilot.server";
    /** 匹配 com.pilot.server.某包.某类，包名是小写段，遇到大写开头的类名（或 import 的 *）为止。 */
    private static final Pattern REFERENCE =
        Pattern.compile("com\\.pilot\\.server((?:\\.[a-z][a-z0-9_]*)+)\\.(?:[A-Z]\\w*|\\*)");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^package ([\\w.]+);");
    /** 文本块、字符串、字符、注释。这些地方出现的类名不算依赖，统一先去掉。 */
    private static final Pattern NOISE = Pattern
        .compile("(?s)\"\"\".*?\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])'|/\\*.*?\\*/|//[^\\n]*");

    /** 包 -> 它引用的其它包 -> 是哪些类引用的（报错时用）。包名去掉了 com.pilot.server 前缀。 */
    private static Map<String, Map<String, Set<String>>> graph;

    @BeforeAll
    static void scanSources() throws IOException {
        Path root = Path.of(System.getProperty("basedir", "."), "src", "main", "java", "com", "pilot", "server");
        assertTrue(Files.isDirectory(root), "找不到主代码目录：" + root.toAbsolutePath());
        graph = new TreeMap<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(path -> path.toString().endsWith(".java")).toList();
        }
        for (Path file : files) {
            String source = NOISE.matcher(Files.readString(file, StandardCharsets.UTF_8)).replaceAll(" ");
            Matcher declared = PACKAGE.matcher(source);
            assertTrue(declared.find(), "没有 package 声明：" + file);
            String from = shortName(declared.group(1));
            graph.computeIfAbsent(from, key -> new TreeMap<>());
            Matcher reference = REFERENCE.matcher(source);
            while (reference.find()) {
                String to = reference.group(1).substring(1);
                if (!to.equals(from)) {
                    graph.get(from).computeIfAbsent(to, key -> new TreeSet<>())
                        .add(file.getFileName().toString().replace(".java", ""));
                }
            }
        }
    }

    @Test
    void packagesHaveNoCycles() {
        List<String> problems = new ArrayList<>();
        for (String from : graph.keySet()) {
            for (String to : graph.get(from).keySet()) {
                if (from.compareTo(to) < 0 && reaches(to, from)) {
                    problems.add(
                        from + " 与 " + to + " 在同一个依赖环里（" + from + " -> " + to + " 来自 " + graph.get(from).get(to) + "）");
                }
            }
        }
        assertTrue(problems.isEmpty(), "包之间出现循环依赖：\n" + String.join("\n", problems));
    }

    @Test
    void commonDependsOnNoBusinessPackage() {
        assertOnlyDependsOn("common.data", Set.of());
        assertOnlyDependsOn("common.web", Set.of());
    }

    @Test
    void configAndStoreStayAtTheBottom() {
        // config 只放跨业务的配置项和不依赖业务的基础装配；store 只放 SQLite 与 core 仓储接口的实现。
        assertOnlyDependsOn("config", Set.of("common.data", "runtime"));
        assertOnlyDependsOn("store", Set.of("common.data"));
    }

    @Test
    void nothingDependsOnTheAgentAssembly() {
        // agent 是把模型、工具、任务控制装到一起的最上层，别的业务只通过 run.RunStarter 接口启动运行。
        List<String> problems = new ArrayList<>();
        graph.forEach((from, targets) -> {
            if (targets.containsKey("agent")) {
                problems.add(from + " 依赖了 agent：" + targets.get("agent"));
            }
        });
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void layeredPackagesAreGone() {
        // 控制器、仓储跟着业务走，不再按技术分层建包。
        for (String retired : List.of("controller", "http", "stream")) {
            assertTrue(!graph.containsKey(retired), "不要再建 " + retired + " 包，类请放到所属业务包里");
        }
    }

    private static void assertOnlyDependsOn(String pkg, Set<String> allowed) {
        assertTrue(graph.containsKey(pkg), "包不存在：" + pkg);
        List<String> problems = new ArrayList<>();
        graph.get(pkg).forEach((to, classes) -> {
            if (!allowed.contains(to)) {
                problems.add(pkg + " 不应该依赖 " + to + "：" + classes);
            }
        });
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /** 从 start 出发顺着依赖能不能走到 target。 */
    private static boolean reaches(String start, String target) {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current.equals(target)) {
                return true;
            }
            if (seen.add(current)) {
                queue.addAll(graph.getOrDefault(current, Map.of()).keySet());
            }
        }
        return false;
    }

    private static String shortName(String pkg) {
        return pkg.equals(BASE) ? "" : pkg.substring(BASE.length() + 1);
    }
}
