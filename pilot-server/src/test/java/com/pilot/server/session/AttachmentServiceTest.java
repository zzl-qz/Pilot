package com.pilot.server.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.loop.Attachment;
import com.pilot.server.config.AttachmentLimits;

class AttachmentServiceTest {
    @TempDir
    Path root;

    @Test
    void inlinesTextAttachmentIntoContent() {
        AttachmentService service = new AttachmentService(root);

        AttachmentService.Prepared prepared = service.prepare("ses_1", "看看这个类",
            List.of(new AttachmentService.Inbound("A.java", "text/plain", base64("class A {}"))));

        assertTrue(prepared.content().contains("看看这个类"));
        assertTrue(prepared.content().contains("[附件 A.java"));
        assertTrue(prepared.content().contains("class A {}"));
        assertTrue(prepared.content().contains("已保存到"), "文本原件也应保留为可下载附件");
        Path saved = Path.of(prepared.content().split("已保存到 ")[1].split("，")[0].trim());
        assertTrue(Files.exists(saved), "文本附件原件应落盘");
        assertTrue(prepared.attachments().isEmpty(), "文本附件内联即可，不需要再交给模型编码");
    }

    @Test
    void storesImageAttachmentOnDisk() throws IOException {
        AttachmentService service = new AttachmentService(root);
        byte[] png = {(byte)0x89, 'P', 'N', 'G'};

        AttachmentService.Prepared prepared = service.prepare("ses_1", "看图",
            List.of(new AttachmentService.Inbound("shot.png", "image/png", Base64.getEncoder().encodeToString(png))));

        assertEquals(1, prepared.attachments().size());
        Attachment attachment = prepared.attachments().get(0);
        assertEquals("image/png", attachment.mimeType());
        assertTrue(Files.exists(Path.of(attachment.path())), "图片应落盘而不是塞进对话记忆");
        assertTrue(Files.readAllBytes(Path.of(attachment.path())).length == png.length);
    }

    @Test
    void imagePositionsCountOnlyImageAttachments() {
        AttachmentService service = new AttachmentService(root);
        AttachmentService.Prepared prepared = service.prepare("ses_1", "beforeafter",
            List.of(new AttachmentService.Inbound("shot.png", "image/png", base64("png")),
                new AttachmentService.Inbound("notes.txt", "text/plain", base64("note"))),
            List.of(6));

        assertEquals(1, prepared.attachments().size());
        assertEquals(List.of(6), prepared.imagePositions());
        assertTrue(prepared.content().contains("notes.txt"));
    }

    @Test
    void acceptsDataUrlPrefixedPayload() {
        AttachmentService service = new AttachmentService(root);

        AttachmentService.Prepared prepared = service.prepare("ses_1", "", List.of(
            new AttachmentService.Inbound("A.java", "text/plain", "data:text/plain;base64," + base64("int a = 1;"))));

        assertTrue(prepared.content().contains("int a = 1;"));
    }

    @Test
    void inventsPromptWhenOnlyImageHasNoText() {
        AttachmentService service = new AttachmentService(root);

        AttachmentService.Prepared prepared = service.prepare("ses_1", "  ",
            List.of(new AttachmentService.Inbound("shot.png", "image/png", base64("png"))));

        assertTrue(prepared.content().contains("请看随消息附上的文件"));
        assertTrue(prepared.content().contains("shot.png"));
    }

    @Test
    void rejectsTooManyAttachments() {
        AttachmentService service = new AttachmentService(root);
        List<AttachmentService.Inbound> many = new ArrayList<>();
        for (int i = 0; i < AttachmentService.MAX_FILES + 1; i++) {
            many.add(new AttachmentService.Inbound("f" + i + ".png", "image/png", base64("png")));
        }

        IllegalArgumentException error =
            assertThrows(IllegalArgumentException.class, () -> service.prepare("ses_1", "hi", many));
        assertTrue(error.getMessage().contains("最多"), error.getMessage());
    }

    @Test
    void acceptsSixtyFourImageAttachments() {
        AttachmentService service = new AttachmentService(root);
        List<AttachmentService.Inbound> many = new ArrayList<>();
        for (int i = 0; i < AttachmentService.MAX_FILES; i++) {
            many.add(new AttachmentService.Inbound("f" + i + ".png", "image/png", base64("png")));
        }

        AttachmentService.Prepared prepared = service.prepare("ses_1", "看这些图", many);

        assertEquals(64, prepared.attachments().size());
    }

    @Test
    void rejectsOversizeAttachment() {
        AttachmentService service = new AttachmentService(root);
        byte[] big = new byte[AttachmentService.MAX_BYTES + 1];

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.prepare("ses_1",
            "hi",
            List.of(new AttachmentService.Inbound("big.bin", "image/png", Base64.getEncoder().encodeToString(big)))));
        assertTrue(error.getMessage().contains("MB"), error.getMessage());
    }

    @Test
    void rejectsCumulativeAttachmentSizeOverTotalBudget() {
        AttachmentService service = new AttachmentService(root, new AttachmentLimits(64, 2 * 1024 * 1024, 150));
        byte[] file = new byte[100];

        IllegalArgumentException error =
            assertThrows(IllegalArgumentException.class,
                () -> service.prepare("ses_1", "hi", List.of(
                    new AttachmentService.Inbound("a.bin", "image/png", Base64.getEncoder().encodeToString(file)),
                    new AttachmentService.Inbound("b.bin", "image/png", Base64.getEncoder().encodeToString(file)))));
        assertTrue(error.getMessage().contains("总大小"), error.getMessage());
    }

    @Test
    void rejectsOversizedEncodedLengthBeforeDecoding() {
        AttachmentService service = new AttachmentService(root, new AttachmentLimits(64, 1000, 1024 * 1024));
        String oversized = "A".repeat(3000);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.prepare("ses_1",
            "hi", List.of(new AttachmentService.Inbound("a.txt", "text/plain", oversized))));
        assertTrue(error.getMessage().contains("超过"), error.getMessage());
    }

    @Test
    void storesBinaryAndPointsToItsPath() throws IOException {
        AttachmentService service = new AttachmentService(root);
        byte[] pdf = {'%', 'P', 'D', 'F', '-', '1', '.', '7', 0, 1, 2};

        AttachmentService.Prepared prepared = service.prepare("ses_1", "帮我看看这份文档", List
            .of(new AttachmentService.Inbound("doc.pdf", "application/pdf", Base64.getEncoder().encodeToString(pdf))));

        assertTrue(prepared.content().contains("doc.pdf"));
        assertTrue(prepared.content().contains("已保存到"));
        assertTrue(prepared.attachments().isEmpty(), "二进制不该走多模态附件通道");
        Path saved = Path.of(prepared.content().split("已保存到 ")[1].split("，")[0].trim());
        assertTrue(Files.exists(saved), "二进制应落盘");
    }

    @Test
    void resolvesOnlyRegularAttachmentsOwnedByTheSession() throws IOException {
        AttachmentService service = new AttachmentService(root);
        Path directory = root.resolve("ses_1");
        Files.createDirectories(directory);
        String fileName = "12345678-1234-1234-1234-123456789abc-report.docx";
        Path saved = Files.writeString(directory.resolve(fileName), "docx");

        assertEquals(saved, service.resolveAttachment("ses_1", fileName));
        assertThrows(IllegalArgumentException.class, () -> service.resolveAttachment("ses_2", fileName));
        assertThrows(IllegalArgumentException.class, () -> service.resolveAttachment("ses_1", "../" + fileName));
    }

    @Test
    void sniffsUnknownTypeAsText() {
        AttachmentService service = new AttachmentService(root);
        byte[] csv = "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8);

        AttachmentService.Prepared prepared =
            service.prepare("ses_1", "看数据", List.of(new AttachmentService.Inbound("data.unknownext",
                "application/octet-stream", Base64.getEncoder().encodeToString(csv))));

        assertTrue(prepared.content().contains("a,b"), "可解码文本应内联");
    }

    @Test
    void rejectsInvalidBase64() {
        AttachmentService service = new AttachmentService(root);

        assertThrows(IllegalArgumentException.class, () -> service.prepare("ses_1", "hi",
            List.of(new AttachmentService.Inbound("a.txt", "text/plain", "!!!not-base64!!!"))));
    }

    @Test
    void invalidLaterAttachmentDoesNotLeaveEarlierFiles() throws IOException {
        AttachmentService service = new AttachmentService(root);

        assertThrows(IllegalArgumentException.class,
            () -> service.prepare("ses_1", "hi",
                List.of(new AttachmentService.Inbound("image.png", "image/png", base64("png")),
                    new AttachmentService.Inbound("broken.png", "image/png", "!!!not-base64!!!"))));

        Path sessionDir = root.resolve("ses_1");
        assertFalse(Files.exists(sessionDir), "验证失败时不应写入任何附件");
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
