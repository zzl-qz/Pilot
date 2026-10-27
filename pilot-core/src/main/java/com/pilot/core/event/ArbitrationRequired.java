package com.pilot.core.event;

import java.util.List;

/**
 * 请求人工仲裁：分歧僵持、变更冲突或硬校验失败时，父 Agent 必须停在这里等人决策。
 *
 * @param arbitrationId 仲裁标识，决策回执时对账
 * @param kind          {@code dissent} / {@code conflict} / {@code verification}
 * @param taskId        相关任务；跨任务冲突时为空字符串
 * @param options       可选操作
 * @param report        结构化报告正文（Markdown）
 */
public record ArbitrationRequired(String arbitrationId, String kind, String taskId,
                                  List<String> options, String report) implements SessionEvent {
    public ArbitrationRequired {
        arbitrationId = arbitrationId == null ? "" : arbitrationId;
        kind = kind == null ? "" : kind;
        taskId = taskId == null ? "" : taskId;
        options = options == null ? List.of() : List.copyOf(options);
        report = report == null ? "" : report;
    }

    @Override
    public EventType type() {
        return EventType.ARBITRATION_REQUIRED;
    }
}
