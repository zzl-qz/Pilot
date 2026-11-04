package com.pilot.server.validation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.pilot.core.multi.DiffSandbox;

/**
 * 工作区产物的稳定指纹。
 *
 * <p>用于把校验结果绑定到「当时那份文件内容」。文件被删、被改、新增，指纹都会变，
 * 所以旧证据不会在代码变化后继续被当成通过。只读取文件，不修改任何东西。
 */
public final class WorkspaceFingerprint {
    private WorkspaceFingerprint() {
    }

    /** 没有改动文件时返回空串：它和任何非空指纹都不相等，语义是「没有可校验的产物」。 */
    public static String of(Path workspace, List<String> paths) {
        if (workspace == null || paths == null || paths.isEmpty()) {
            return "";
        }
        Path root = workspace.toAbsolutePath().normalize();
        List<String> entries = new ArrayList<>();
        for (String relative : paths) {
            if (relative == null || relative.isBlank()) {
                continue;
            }
            Path file;
            try {
                file = root.resolve(relative).normalize();
            } catch (RuntimeException e) {
                continue;
            }
            if (!file.startsWith(root)) {
                entries.add(relative + "\0OUTSIDE");
                continue;
            }
            if (!Files.isRegularFile(file)) {
                entries.add(relative + "\0MISSING");
                continue;
            }
            try {
                entries.add(relative + "\0" + DiffSandbox.fingerprint(Files.readString(file, StandardCharsets.UTF_8)));
            } catch (IOException | RuntimeException e) {
                entries.add(relative + "\0UNREADABLE");
            }
        }
        entries.sort(String::compareTo);
        return entries.isEmpty() ? "" : DiffSandbox.fingerprint(String.join("\n", entries));
    }
}
