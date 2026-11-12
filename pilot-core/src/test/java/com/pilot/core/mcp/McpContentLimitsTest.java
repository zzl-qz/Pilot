package com.pilot.core.mcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.ObjectMapper;

class McpContentLimitsTest {
    @TempDir
    Path workspace;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void frameBoundaryCountsBytesAndDoesNotConsumeNextFrame() throws Exception {
        byte[] utf8 = "中文".getBytes(StandardCharsets.UTF_8);
        var in = new ByteArrayInputStream("中文\n{}\r\n".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(utf8, McpJson.readFrame(in, utf8.length));
        assertArrayEquals("{}\r".getBytes(StandardCharsets.UTF_8), McpJson.readFrame(in, 3));
        assertNull(McpJson.readFrame(in, 3));
        assertThrows(McpException.class, () -> McpJson.readFrame(new ByteArrayInputStream(utf8), 5));
        assertThrows(McpException.class, () -> McpJson.readFrame(new ByteArrayInputStream(utf8), 6));
    }

    @Test
    void tokenAndNumberLimitsRejectSmallButExpensiveJson() {
        assertThrows(McpException.class,
            () -> McpJson.parse(("[" + "0,".repeat(100_000) + "0]").getBytes(StandardCharsets.UTF_8)));
        assertThrows(McpException.class, () -> McpJson.parse("1".repeat(129).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void decodedSizeMatchesPaddedAndUnpaddedBoundaries() {
        for (int size : List.of(0, 1, 2, 3, 4, 5, 1024, (int)McpCallTool.MAX_IMAGE_BYTES,
            (int)McpCallTool.MAX_IMAGE_BYTES + 1)) {
            byte[] bytes = new byte[size];
            assertEquals(size, McpCallTool.decodedSize(Base64.getEncoder().encodeToString(bytes)));
            assertEquals(size, McpCallTool.decodedSize(Base64.getEncoder().withoutPadding().encodeToString(bytes)));
        }
    }

    @Test
    void configurationIsBoundedBeforeJsonParsing() throws Exception {
        Path config = workspace.resolve("large.json");
        Files.writeString(config, " ".repeat(McpJson.MAX_CONFIG_BYTES + 1));
        assertThrows(McpException.class, () -> McpJson.readConfig(config));
    }

    @Test
    void imageCountAndTotalDecodedBytesAreBounded() {
        String previous = System.getProperty("pilot.artifacts.dir");
        System.setProperty("pilot.artifacts.dir", workspace.toString());
        try (McpManager manager = new McpManager(mapper, null, null, null)) {
            var tool = new McpCallTool(manager, workspace);
            var tiny = Map.of("type", "image", "mimeType", "image/png", "data", "AA==");
            var count = tool.render("fake", mapper.valueToTree(Map.of("content", Collections.nCopies(9, tiny))));
            assertEquals(8, count.images().size());
            assertTrue(count.text().contains("数量超过上限"));
            var large = Map.of("type", "image", "mimeType", "image/png", "data",
                Base64.getEncoder().encodeToString(new byte[3 * 1024 * 1024]));
            var bytes = tool.render("fake", mapper.valueToTree(Map.of("content", List.of(large, large))));
            assertEquals(1, bytes.images().size());
            assertTrue(bytes.text().contains("累计大小上限"));
        } finally {
            if (previous == null) {
                System.clearProperty("pilot.artifacts.dir");
            } else {
                System.setProperty("pilot.artifacts.dir", previous);
            }
        }
    }

    @Test
    void oversizedImageIsRejectedBeforeInvalidBase64IsDecoded() {
        try (McpManager manager = new McpManager(mapper, null, null, null)) {
            var tool = new McpCallTool(manager, workspace);
            var result = tool.render("fake", mapper.valueToTree(Map.of("content",
                List.of(Map.of("type", "image", "mimeType", "image/png", "data", "!".repeat(7 * 1024 * 1024))))));
            assertTrue(result.text().contains("解码前拒绝"), result.text());
            assertTrue(result.images().isEmpty());
        }
    }
}
