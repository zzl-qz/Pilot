package com.pilot.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.pilot.core.loop.Attachment;

class QueuedMessageCodecTest {
    @Test
    void plainTextStaysPlainSoOldQueuesStillLoad() {
        QueuedMessageCodec.Stored plain = new QueuedMessageCodec.Stored("下一件事", List.of(), List.of());

        assertEquals("下一件事", QueuedMessageCodec.encode(plain));
        assertEquals(plain, QueuedMessageCodec.decode("下一件事"));
    }

    @Test
    void imagesRoundTripWithPositions() {
        Attachment image = new Attachment("shot.png", "image/png", "/data/attachments/s/shot.png");
        QueuedMessageCodec.Stored stored = new QueuedMessageCodec.Stored("看图\n第二行", List.of(image), List.of(2));

        String raw = QueuedMessageCodec.encode(stored);

        assertTrue(raw.startsWith("\u0001"), "带附件的消息要和纯文本区分开");
        assertEquals(stored, QueuedMessageCodec.decode(raw));
    }

    @Test
    void corruptedRecordDegradesToAVisibleNoticeInsteadOfBreakingTheQueue() {
        QueuedMessageCodec.Stored decoded = QueuedMessageCodec.decode("\u0001pilot-queue-v1\u0001{not json");

        assertTrue(decoded.content().contains("已损坏"));
        assertTrue(decoded.attachments().isEmpty());
    }
}
