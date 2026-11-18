package com.pilot.core.lint;

import java.util.Locale;
import java.util.Set;

import com.pilot.core.util.FileNames;

/**
 * 判断一个相对路径是不是生产源码。
 * 测试、压缩脚本和构建产物不算，lint 只把本次改动里的生产源码交给检查命令。
 */
public final class SourceFiles {
    private static final Set<String> CODE_EXTS = Set.of("java", "kt", "kts", "js", "jsx", "ts", "tsx", "mjs", "cjs",
        "py", "go", "rs", "cs", "cpp", "cc", "c", "h", "swift");
    private static final Set<String> SKIPPED_DIRS = Set.of("test", "tests", "spec", "specs", "__tests__", "fixture",
        "fixtures", "target", "build", "dist", "out", "node_modules", ".pilot");

    private SourceFiles() {
    }

    public static boolean isProductionSource(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String norm = path.replace('\\', '/');
        String lower = norm.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".min.js") || lower.endsWith(".bundle.js") || lower.endsWith(".d.ts")) {
            return false;
        }
        if (!CODE_EXTS.contains(FileNames.extension(norm))) {
            return false;
        }
        return !isTestPath(norm);
    }

    /** 测试源码：目录名或文件名像测试。给边界任务核对「有没有补测试」用。 */
    public static boolean isTestSource(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String norm = path.replace('\\', '/');
        if (!CODE_EXTS.contains(FileNames.extension(norm))) {
            return false;
        }
        String[] parts = norm.split("/");
        if (isTestFile(parts[parts.length - 1])) {
            return true;
        }
        for (int i = 0; i < parts.length - 1; i++) {
            String name = parts[i].toLowerCase(Locale.ROOT);
            if (name.equals("test") || name.equals("tests") || name.equals("spec") || name.equals("specs")
                || name.equals("__tests__")) {
                return true;
            }
        }
        return false;
    }

    static boolean isTestPath(String norm) {
        String[] parts = norm.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (SKIPPED_DIRS.contains(parts[i].toLowerCase(Locale.ROOT))) {
                return true;
            }
            if (i == parts.length - 1 && isTestFile(parts[i])) {
                return true;
            }
        }
        return false;
    }

    static boolean isTestFile(String filename) {
        int dot = filename.lastIndexOf('.');
        String stem = dot < 0 ? filename : filename.substring(0, dot);
        String lower = stem.toLowerCase(Locale.ROOT);
        return stem.endsWith("Test") || stem.endsWith("Tests") || stem.endsWith("IT") || stem.endsWith("Spec")
            || stem.endsWith("Specs") || lower.endsWith(".test") || lower.endsWith(".spec") || lower.endsWith("_test")
            || lower.startsWith("test_");
    }
}
