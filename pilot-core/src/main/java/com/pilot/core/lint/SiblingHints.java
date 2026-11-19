package com.pilot.core.lint;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次运行里给写入结果附上同目录名字。
 * 每个生产源码文件只提示一次，整轮最多 {@link #MAX_FILES} 个，避免反复提醒把模型拖进改写循环。
 */
public final class SiblingHints {
    /** 一轮里最多提示这么多个文件；再多就不再扫描。 */
    public static final int MAX_FILES = 8;
    static final String TAIL = "只供对照。业务域或量纲对不上就不要引用，也不要为此新建包装或反复改写。每个文件本轮只提示一次。";

    private final Set<String> seen = new LinkedHashSet<>();
    private int shown;

    /**
     * 为本批改动附上还没提示过的同目录名字。没有可提示的内容时返回空串，不改变工具成败。
     */
    public synchronized String note(Path workspace, List<String> changedPaths) {
        if (workspace == null || changedPaths == null || changedPaths.isEmpty() || shown >= MAX_FILES) {
            return "";
        }
        StringBuilder body = new StringBuilder();
        for (String path : changedPaths) {
            if (shown >= MAX_FILES) {
                break;
            }
            String key = path == null ? "" : path.replace('\\', '/').strip();
            if (key.isEmpty() || !seen.add(key) || !SourceFiles.isProductionSource(key)) {
                continue;
            }
            String names = SiblingNames.describe(workspace, key);
            if (names.isBlank()) {
                continue;
            }
            if (!body.isEmpty()) {
                body.append('\n');
            }
            body.append(key).append(" 的同目录已有 ").append(names);
            shown++;
        }
        if (body.isEmpty()) {
            return "";
        }
        return "\n\n[同目录已有名字]\n" + body + "\n" + TAIL;
    }
}
