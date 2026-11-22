package com.pilot.core.coordination;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 发布一条工作区事件时提交的内容，不含序号与时间：序号由发布器按发布顺序分配。
 *
 * <p>这里只允许放短摘要：{@code subject} 是给界面看的一句话，{@code details} 是少量键值，
 * 不允许写入文件正文、令牌或别的会话私有数据。
 *
 * @param projectId 事件所属项目；个人会话为 {@code null}
 * @param directory 受影响目录的绝对路径；只有项目级信息时可以为 {@code null}
 */
public record WorkspaceEventDraft(WorkspaceEventType type, String projectId, String sessionId, String directory,
    String subject, Map<String, String> details) {

    public WorkspaceEventDraft {
        Objects.requireNonNull(type, "type must not be null");
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        details = details == null || details.isEmpty() ? Map.of() : Map.copyOf(details);
    }

    public static WorkspaceEventDraft of(WorkspaceEventType type, String sessionId, String directory,
        String subject) {
        return new WorkspaceEventDraft(type, null, sessionId, directory, subject, Map.of());
    }

    /** 便于调用方逐步补充字段，返回新实例，不修改自身。 */
    public WorkspaceEventDraft withProject(String projectId) {
        return new WorkspaceEventDraft(type, projectId, sessionId, directory, subject, details);
    }

    public WorkspaceEventDraft withDirectory(String directory) {
        return new WorkspaceEventDraft(type, projectId, sessionId, directory, subject, details);
    }

    public WorkspaceEventDraft withSubject(String subject) {
        return new WorkspaceEventDraft(type, projectId, sessionId, directory, subject, details);
    }

    public WorkspaceEventDraft withDetail(String key, String value) {
        Map<String, String> merged = new LinkedHashMap<>(details);
        if (value == null) {
            merged.remove(key);
        } else {
            merged.put(key, value);
        }
        return new WorkspaceEventDraft(type, projectId, sessionId, directory, subject, merged);
    }
}
