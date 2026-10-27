package com.pilot.core.session;

import java.time.Instant;
import java.util.Objects;

/**
 * 会话（对话 Thread）快照。事件列表通过 {@link SessionService#events(String)} 读取。
 *
 * @param workspace 会话当前使用的本地工作区绝对路径；为 {@code null} 表示使用服务端默认工作区
 * @param projectId 所属项目；为 {@code null} 表示历史数据（项目制改造之前创建的会话）
 * @param isBlank   还没有发过任何消息。同一项目下最多只有一条未归档的空白对话
 * @param archived  已归档：不再出现在主列表，但数据和事件都保留
 */
public record Session(String id, String title, Instant createdAt, SessionStatus status, String workspace,
                      String projectId, boolean isBlank, boolean archived) {
    /** 兼容旧调用：不归属项目，也不是空白对话。 */
    public Session(String id, String title, Instant createdAt, SessionStatus status, String workspace) {
        this(id, title, createdAt, status, workspace, null, false, false);
    }

    /** 兼容旧调用：不归属项目的普通对话。 */
    public Session(String id, String title, Instant createdAt, SessionStatus status, String workspace,
                   String projectId) {
        this(id, title, createdAt, status, workspace, projectId, false, false);
    }

    public Session {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(status, "status must not be null");
        title = title.strip();
        if (workspace != null) {
            workspace = workspace.strip();
            if (workspace.isEmpty()) {
                workspace = null;
            }
        }
        if (projectId != null) {
            projectId = projectId.strip();
            if (projectId.isEmpty()) {
                projectId = null;
            }
        }
    }

    /** 发过消息之后就不能再变回空白对话。 */
    public Session withBlank(boolean blank) {
        return new Session(id, title, createdAt, status, workspace, projectId, blank, archived);
    }

    public Session withArchived(boolean value) {
        return new Session(id, title, createdAt, status, workspace, projectId, isBlank, value);
    }

    public Session withTitle(String newTitle) {
        return new Session(id, newTitle, createdAt, status, workspace, projectId, isBlank, archived);
    }

    public Session withWorkspace(String newWorkspace) {
        return new Session(id, title, createdAt, status, newWorkspace, projectId, isBlank, archived);
    }
}
