package com.pilot.core.lint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 从同目录的生产源码里抽出少量导出名字，供写入结果对照。
 * 只读短行，不解析压缩产物，任何读失败都返回空串。
 */
final class SiblingNames {
    static final int MAX_SIBLINGS = 8;
    static final int MAX_NAMES_PER_FILE = 6;
    static final int MAX_NAMES = 24;
    private static final int MAX_FILE_BYTES = 48 * 1024;
    private static final int MAX_LINE_CHARS = 180;
    private static final int MAX_LINES = 500;
    private static final int LIST_LIMIT = 40;
    private static final Pattern CONSTANT = Pattern.compile("\\b([A-Z][A-Z0-9_]{2,40})\\b\\s*=");
    private static final Pattern METHOD = Pattern
        .compile("\\b(?:public|protected)\\s+(?:static\\s+)?[\\w<>,.?\\[\\]]+\\s+([a-z][A-Za-z0-9]{1,40})\\s*\\(");
    private static final Pattern EXPORTED = Pattern.compile(
        "^\\s*export\\s+(?:default\\s+)?(?:async\\s+)?(?:function|const|class|type|interface|enum)\\s+([A-Za-z_]\\w{0,40})");
    private static final Pattern PYTHON = Pattern.compile("^def\\s+([a-z_]\\w{1,40})\\s*\\(");
    private static final Pattern GO = Pattern.compile("^func\\s+(?:\\([^)]{0,80}\\)\\s+)?([A-Z]\\w{1,40})\\s*\\(");

    private SiblingNames() {
    }

    /** 同目录其他生产源码的名字；没有、越界或读失败时返回空串。 */
    static String describe(Path workspace, String relativePath) {
        try {
            return scan(workspace, relativePath);
        } catch (RuntimeException | IOException e) {
            return "";
        }
    }

    private static String scan(Path workspace, String relativePath) throws IOException {
        if (workspace == null || relativePath == null || !SourceFiles.isProductionSource(relativePath)) {
            return "";
        }
        Path root = workspace.toAbsolutePath().normalize();
        Path file = root.resolve(relativePath.replace('\\', '/')).normalize();
        if (!file.startsWith(root)) {
            return "";
        }
        Path dir = file.getParent();
        if (dir == null || !dir.startsWith(root) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        List<String> rows = new ArrayList<>();
        int names = 0;
        try (Stream<Path> list = Files.list(dir)) {
            for (Path candidate : list.limit(LIST_LIMIT).toList()) {
                if (rows.size() >= MAX_SIBLINGS || names >= MAX_NAMES) {
                    break;
                }
                if (candidate.equals(file) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (Files.size(candidate) > MAX_FILE_BYTES) {
                    continue;
                }
                String relative = root.relativize(candidate).toString().replace('\\', '/');
                if (!SourceFiles.isProductionSource(relative)) {
                    continue;
                }
                List<String> found = namesIn(candidate);
                if (found.isEmpty()) {
                    continue;
                }
                names += found.size();
                rows.add(candidate.getFileName() + ": " + String.join(", ", found));
            }
        }
        return String.join("；", rows);
    }

    private static List<String> namesIn(Path file) {
        Set<String> names = new LinkedHashSet<>();
        try {
            List<String> lines = Files.readAllLines(file);
            int seen = 0;
            for (String raw : lines) {
                if (seen++ >= MAX_LINES || names.size() >= MAX_NAMES_PER_FILE) {
                    break;
                }
                String line = raw.strip();
                if (line.length() > MAX_LINE_CHARS || line.startsWith("//") || line.startsWith("*")
                    || line.startsWith("/*") || line.startsWith("#")) {
                    continue;
                }
                collect(EXPORTED.matcher(line), names);
                collect(PYTHON.matcher(line), names);
                collect(GO.matcher(line), names);
                collect(CONSTANT.matcher(line), names);
                collect(METHOD.matcher(line), names);
            }
        } catch (IOException e) {
            return List.of();
        }
        return List.copyOf(names);
    }

    private static void collect(Matcher matcher, Set<String> names) {
        if (names.size() >= MAX_NAMES_PER_FILE) {
            return;
        }
        if (matcher.find()) {
            names.add(matcher.group(1));
        }
    }
}
