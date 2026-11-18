package com.pilot.core.tool.file;

import java.nio.file.Path;

import com.pilot.core.tool.ReadGuard;
import com.pilot.core.tool.SandboxSink;
import com.pilot.core.workspace.PathValidator;

/**
 * 沙箱版 {@code create_file}：新文件同样只进沙箱，等用户接受变更后才真正创建。
 *
 * <p>已存在的文件仍然拒绝创建，提示改用 {@code edit_file}，与真实写工具保持一致。
 */
public final class SandboxCreateFileTool extends CreateFileOperation {
    private final SandboxSink sandbox;

    public SandboxCreateFileTool(PathValidator paths, SandboxSink sandbox) {
        this(paths, sandbox, ReadGuard.allowAll());
    }

    public SandboxCreateFileTool(PathValidator paths, SandboxSink sandbox, ReadGuard reads) {
        super(paths, reads);
        this.sandbox = java.util.Objects.requireNonNull(sandbox, "sandbox must not be null");
    }

    @Override
    boolean existsInSandbox(Path file) {
        return sandbox.proposed(paths.display(file));
    }

    @Override
    String create(Path file, String rawPath, String content) {
        try {
            sandbox.record(paths.display(file), null, content);
            // 刚创建的文件视为已读：后续 edit_file 不会被「先读后改」拦截
            reads.observed(file);
            return "已提交到沙箱 " + paths.display(file) + "（新文件，" + (int)content.lines().count() + " 行）";
        } catch (RuntimeException e) {
            return "ERROR: 提交沙箱失败: " + e.getMessage();
        }
    }
}
