package com.pilot.core.coordination;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 已发布的工作区协调事件：不可变，发布后只读。
 *
 * <p>事件只带定位信息与短摘要，不带文件内容：{@code subject} 与 {@code details} 由发布方保证
 * 不含文件正文、令牌或别的会话私有数据。可见性由 {@link #visibleTo} 判定：
 * 目录要落在订阅者范围内，项目标识不能互相冲突。
 *
 * @param sequence  发布顺序号，从 1 开始；客户端按它去重与续传
 * @param directory 受影响目录或文件的绝对路径；只有项目级信息时为 {@code null}
 */
public record WorkspaceCoordinationEvent(long sequence, Instant timestamp, WorkspaceEventType type, String projectId,
    String sessionId, String directory, String subject, Map<String, String> details) {

    /** 事件信封自身的基础开销，估算缓存大小时计入。 */
    private static final int ENVELOPE_BYTES = 96;

    public WorkspaceCoordinationEvent {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must start at 1, but was " + sequence);
        }
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(type, "type must not be null");
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        subject = subject == null ? "" : subject;
        details = details == null || details.isEmpty() ? Map.of() : Map.copyOf(details);
    }

    public String wireType() {
        return type.wireName();
    }

    /**
     * 订阅者能否看到这条事件：目录必须落在自己的范围里，且不能跨项目。
     *
     * <p>没有目录的事件（项目级）只给同一项目的订阅者；订阅者没有项目标识时按目录判断。
     */
    public boolean visibleTo(ProjectCoordinationScope scope) {
        if (scope == null || scope.isEmpty()) {
            return false;
        }
        if (projectId != null && scope.projectId() != null && !projectId.equals(scope.projectId())) {
            return false;
        }
        if (directory == null) {
            return projectId != null && projectId.equals(scope.projectId());
        }
        try {
            return scope.includes(Path.of(directory));
        } catch (InvalidPathException invalid) {
            return false;
        }
    }

    /** 估算占用字节数，用于有界历史与单条事件上限；只按字符数粗略计算。 */
    public int estimatedBytes() {
        long size = ENVELOPE_BYTES + textLength(sessionId) + textLength(projectId) + textLength(directory)
            + textLength(subject);
        for (Map.Entry<String, String> detail : details.entrySet()) {
            size += textLength(detail.getKey()) + textLength(detail.getValue()) + 8;
        }
        return (int)Math.min(Integer.MAX_VALUE, size);
    }

    /**
     * 截断到上限之内：先丢细节、再截摘要，仍放不下时退化成项目级事件。
     *
     * <p>截断只发生在发布时，保证超大事件不会把缓存或连接撑爆，也不会把文件内容带出去。
     */
    public WorkspaceCoordinationEvent compacted(int maxBytes) {
        if (estimatedBytes() <= maxBytes) {
            return this;
        }
        String shortSubject = truncate(subject, Math.max(0, maxBytes / 8));
        WorkspaceCoordinationEvent withoutDetails = new WorkspaceCoordinationEvent(sequence, timestamp, type, projectId,
            sessionId, directory, shortSubject, Map.of());
        if (withoutDetails.estimatedBytes() <= maxBytes) {
            return withoutDetails;
        }
        return new WorkspaceCoordinationEvent(sequence, timestamp, type, projectId, sessionId, null, "", Map.of());
    }

    private static String truncate(String text, int limit) {
        if (text == null || text.length() <= limit) {
            return text == null ? "" : text;
        }
        if (limit <= 0) {
            return "";
        }
        int end = text.offsetByCodePoints(0, Math.min(limit, text.codePointCount(0, text.length())));
        return text.substring(0, end);
    }

    private static int textLength(String text) {
        return text == null ? 0 : text.length();
    }
}
