package com.pilot.server.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AttachmentServiceTest {
    @TempDir
    Path root;

    @Test
    void inlinesTextAttachmentIntoContent() {
        var service = new AttachmentService(root);

        var prepared = service.prepare("ses_1", "看看这个类",
                List.of(new AttachmentService.Inbound("A.java", "text/plain", base64("class A {}"))));

        assertTrue(prepared.content().contains("看看这个类"));
        assertTrue(prepared.content().contains("[附件 A.java"));
        assertTrue(prepared.content().contains("class A {}"));
        assertTrue(prepared.attachments().isEmpty(), "文本附件内联即可，不需要再交给模型编码");
    }

    @Test
    void storesImageAttachmentOnDisk() throws IOException {
        var service = new AttachmentService(root);
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};

        var prepared = service.prepare("ses_1", "看图", List.of(
                new AttachmentService.Inbound("shot.png", "image/png", Base64.getEncoder().encodeToString(png))));

        assertEquals(1, prepared.attachments().size());
        var attachment = prepared.attachments().get(0);
        assertEquals("image/png", attachment.mimeType());
        assertTrue(Files.exists(Path.of(attachment.path())), "图片应落盘而不是塞进对话记忆");
        assertTrue(Files.readAllBytes(Path.of(attachment.path())).length == png.length);
    }

    @Test
    void acceptsDataUrlPrefixedPayload() {
        var service = new AttachmentService(root);

        var prepared = service.prepare("ses_1", "",
                List.of(new AttachmentService.Inbound("A.java", "text/plain",
                        "data:text/plain;base64," + base64("int a = 1;"))));

        assertTrue(prepared.content().contains("int a = 1;"));
    }

    @Test
    void inventsPromptWhenOnlyImageHasNoText() {
        var service = new AttachmentService(root);

        var prepared = service.prepare("ses_1", "  ", List.of(
                new AttachmentService.Inbound("shot.png", "image/png", base64("png"))));

        assertTrue(prepared.content().contains("请看随消息附上的文件"));
        assertTrue(prepared.content().contains("shot.png"));
    }

    @Test
    void rejectsTooManyAttachments() {
        var service = new AttachmentService(root);
        List<AttachmentService.Inbound> many = new ArrayList<>();
        for (int i = 0; i < AttachmentService.MAX_FILES + 1; i++) {
            many.add(new AttachmentService.Inbound("f" + i + ".txt", "text/plain", base64("x")));
        }

        var error = assertThrows(IllegalArgumentException.class, () -> service.prepare("ses_1", "hi", many));
        assertTrue(error.getMessage().contains("最多"), error.getMessage());
    }

    @Test
    void rejectsOversizeAttachment() {
        var service = new AttachmentService(root);
        byte[] big = new byte[AttachmentService.MAX_BYTES + 1];

        var error = assertThrows(IllegalArgumentException.class, () -> service.prepare("ses_1", "hi",
                List.of(new AttachmentService.Inbound("big.bin", "image/png",
                        Base64.getEncoder().encodeToString(big)))));
        assertTrue(error.getMessage().contains("MB"), error.getMessage());
    }

    @Test
    void storesBinaryAndPointsToItsPath() throws IOException {
        var service = new AttachmentService(root);
        byte[] pdf = {'%', 'P', 'D', 'F', '-', '1', '.', '7', 0, 1, 2};

        var prepared = service.prepare("ses_1", "帮我看看这份文档", List.of(
                new AttachmentService.Inbound("doc.pdf", "application/pdf",
                        Base64.getEncoder().encodeToString(pdf))));

        assertTrue(prepared.content().contains("doc.pdf"));
        assertTrue(prepared.content().contains("已保存到"));
        assertTrue(prepared.attachments().isEmpty(), "二进制不该走多模态附件通道");
        Path saved = Path.of(prepared.content().split("已保存到 ")[1].split("，")[0].trim());
        assertTrue(Files.exists(saved), "二进制应落盘");
    }

    @Test
    void sniffsUnknownTypeAsText() {
        var service = new AttachmentService(root);
        byte[] csv = "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8);

        var prepared = service.prepare("ses_1", "看数据", List.of(
                new AttachmentService.Inbound("data.unknownext", "application/octet-stream",
                        Base64.getEncoder().encodeToString(csv))));

        assertTrue(prepared.content().contains("a,b"), "可解码文本应内联");
    }

    @Test
    void rejectsInvalidBase64() {
        var service = new AttachmentService(root);

        assertThrows(IllegalArgumentException.class, () -> service.prepare("ses_1", "hi",
                List.of(new AttachmentService.Inbound("a.txt", "text/plain", "!!!not-base64!!!"))));
    }

    @Test
    void invalidLaterAttachmentDoesNotLeaveEarlierFiles() throws IOException {
        var service = new AttachmentService(root);

        assertThrows(IllegalArgumentException.class, () -> service.prepare("ses_1", "hi", List.of(
                new AttachmentService.Inbound("image.png", "image/png", base64("png")),
                new AttachmentService.Inbound("broken.png", "image/png", "!!!not-base64!!!"))));

        Path sessionDir = root.resolve("ses_1");
        assertFalse(Files.exists(sessionDir), "验证失败时不应写入任何附件");
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
