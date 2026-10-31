package com.pilot.server.session;

import com.pilot.core.loop.Attachment;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * 排队消息在 {@code queued_messages.content} 里的存储格式。
 *
 * <p>没有图片附件的消息仍是纯文本，和旧数据完全一致；带图片的消息存成一个固定前缀加 JSON，
 * 这样不改表结构，旧版本写入的队列也能原样读出来。文本文件已经内联进正文，不需要额外存。
 */
final class QueuedMessageCodec {
    private static final String PREFIX = "\u0001pilot-queue-v1\u0001";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private QueuedMessageCodec() {
    }

    record Stored(String content, List<Attachment> attachments, List<Integer> imagePositions) {
        Stored {
            content = content == null ? "" : content;
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
            imagePositions = imagePositions == null ? List.of() : List.copyOf(imagePositions);
        }

        Stored withContent(String text) {
            return new Stored(text, attachments, imagePositions);
        }
    }

    static String encode(Stored item) {
        if (item.attachments().isEmpty()) {
            return item.content();
        }
        return PREFIX + MAPPER.writeValueAsString(item);
    }

    static Stored decode(String raw) {
        if (raw == null) {
            return new Stored("", List.of(), List.of());
        }
        if (!raw.startsWith(PREFIX)) {
            return new Stored(raw, List.of(), List.of());
        }
        try {
            return MAPPER.readValue(raw.substring(PREFIX.length()), Stored.class);
        } catch (RuntimeException e) {
            // 损坏的记录宁可丢掉附件也不能让整个队列读不出来，正文留一句提示避免空消息启动运行
            return new Stored("（这条排队消息的附件已损坏，请重新发送）", List.of(), List.of());
        }
    }
}
