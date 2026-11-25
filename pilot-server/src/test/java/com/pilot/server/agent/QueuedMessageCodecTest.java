package com.pilot.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pilot.core.message.Attachment;
import com.pilot.server.run.RunStarter.QueueRequirements;

/** 队列条目的存储兼容：旧纯文本、带附件、带约束/验收，以及旧 JSON 缺少新字段。 */
class QueuedMessageCodecTest {
    @Test
    void plainTextStaysPlainTextForOldReaders() {
        String encoded = QueuedMessageCodec.encode(new QueuedMessageCodec.Stored("普通消息", List.of(), List.of()));
        assertEquals("普通消息", encoded);
        assertEquals("普通消息", QueuedMessageCodec.decode(encoded).content());
    }

    @Test
    void requirementsRoundTripAndSurviveWithoutAttachments() {
        var requirements = new QueueRequirements(List.of("别动 pom", "保留 API"), List.of("覆盖失败路径"));
        String encoded =
            QueuedMessageCodec.encode(new QueuedMessageCodec.Stored("补测试", List.of(), List.of(), requirements));
        assertTrue(encoded.startsWith("\u0001pilot-queue-v1\u0001"));
        var decoded = QueuedMessageCodec.decode(encoded);
        assertEquals("补测试", decoded.content());
        assertEquals(List.of("别动 pom", "保留 API"), decoded.requirements().constraints());
        assertEquals(List.of("覆盖失败路径"), decoded.requirements().acceptance());
        assertTrue(decoded.attachments().isEmpty());
    }

    @Test
    void attachmentsAndRequirementsRoundTripTogether() {
        var attachment = new Attachment("a.png", "image/png", "/tmp/a.png");
        var requirements = new QueueRequirements(List.of(), List.of("通过"));
        var decoded = QueuedMessageCodec.decode(QueuedMessageCodec
            .encode(new QueuedMessageCodec.Stored("看图", List.of(attachment), List.of(0), requirements)));
        assertEquals("看图", decoded.content());
        assertEquals(1, decoded.attachments().size());
        assertEquals(List.of(0), decoded.imagePositions());
        assertEquals(List.of("通过"), decoded.requirements().acceptance());
    }

    @Test
    void legacyJsonWithoutRequirementsDecodesAsNoRequirements() {
        String legacy = "\u0001pilot-queue-v1\u0001" + "{\"content\":\"旧消息\",\"attachments\":[],\"imagePositions\":[]}";
        var decoded = QueuedMessageCodec.decode(legacy);
        assertEquals("旧消息", decoded.content());
        assertNull(decoded.requirements());
    }

    @Test
    void blankRequirementLinesAreDropped() {
        var requirements = new QueueRequirements(Arrays.asList("  ", "保留 API ", null), Arrays.asList(""));
        assertEquals(List.of("保留 API"), requirements.constraints());
        assertTrue(requirements.acceptance().isEmpty());
        assertTrue(!requirements.empty());
    }

    @Test
    void skillRoundTripsInQueuedMessageAndLegacyJsonDecodesNullSkill() {
        String encoded = QueuedMessageCodec
            .encode(new QueuedMessageCodec.Stored("审阅本次改动", List.of(), List.of(), null, "plan", "code-review"));
        assertTrue(encoded.startsWith("\u0001pilot-queue-v1\u0001"));
        var decoded = QueuedMessageCodec.decode(encoded);
        assertEquals("审阅本次改动", decoded.content());
        assertEquals("plan", decoded.mode());
        assertEquals("code-review", decoded.skill());

        var edited = decoded.withContent("改一下审阅重点");
        assertEquals("code-review", edited.skill(), "编辑排队消息正文不应丢失已绑定的技能");

        String legacy = "\u0001pilot-queue-v1\u0001{\"content\":\"旧消息\",\"attachments\":[],\"imagePositions\":[]}";
        assertNull(QueuedMessageCodec.decode(legacy).skill());
    }
}
