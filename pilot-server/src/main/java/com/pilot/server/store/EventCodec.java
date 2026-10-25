package com.pilot.server.store;

import com.pilot.core.event.ApprovalRequired;
import com.pilot.core.event.ApprovalResolved;
import com.pilot.core.event.AssistantDelta;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunFailed;
import com.pilot.core.event.RunStarted;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.event.ToolCompleted;
import com.pilot.core.event.ToolStarted;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 事件 <-> 数据库行的编解码。
 *
 * <p>库里存 {@code type} + {@code payload}，读回时按 type 派发到具体事件类，
 * 避免依赖 Jackson 多态注解，也让表结构对 SSE 协议本身无感知。
 */
@Component
public class EventCodec {
    private final ObjectMapper mapper;

    public EventCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String encode(SessionEvent event) {
        return mapper.writeValueAsString(event);
    }

    public SessionEvent decode(String type, String payload) {
        Class<? extends SessionEvent> target = switch (type) {
            case "run.started" -> RunStarted.class;
            case "assistant.delta" -> AssistantDelta.class;
            case "tool.started" -> ToolStarted.class;
            case "tool.completed" -> ToolCompleted.class;
            case "approval.required" -> ApprovalRequired.class;
            case "approval.resolved" -> ApprovalResolved.class;
            case "run.completed" -> RunCompleted.class;
            case "run.failed" -> RunFailed.class;
            default -> throw new IllegalArgumentException("未知事件类型: " + type);
        };
        return mapper.readValue(payload, target);
    }
}
