package com.pilot.server.agent;

import java.util.List;

import com.pilot.server.run.RunStarter.QueuedMessage;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 排队消息在 {@code queued_messages.content} 里的存储格式。
 *
 * <p>没有图片附件、约束、模式与技能的消息仍是纯文本，和旧数据完全一致；携带扩展元数据的消息存成固定前缀加 JSON，
 * 不改表结构，旧版本写入的队列也能原样读出来。
 */
final class QueuedMessageCodec {
    private static final String PREFIX = "\u0001pilot-queue-v1\u0001";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private QueuedMessageCodec() {
    }

    static String encode(QueuedMessage item) {
        if (item.attachments().isEmpty() && item.requirements() == null && !"plan".equalsIgnoreCase(item.mode())
            && item.skill() == null) {
            return item.content();
        }
        return PREFIX + MAPPER.writeValueAsString(item);
    }

    static QueuedMessage decode(String raw) {
        if (raw == null) {
            return new QueuedMessage("", List.of(), List.of());
        }
        if (!raw.startsWith(PREFIX)) {
            return new QueuedMessage(raw, List.of(), List.of());
        }
        try {
            return MAPPER.readValue(raw.substring(PREFIX.length()), QueuedMessage.class);
        } catch (RuntimeException e) {
            // 损坏的记录宁可丢掉附件也不能让整个队列读不出来，正文留一句提示避免空消息启动运行
            return new QueuedMessage("（这条排队消息的附件已损坏，请重新发送）", List.of(), List.of());
        }
    }
}
