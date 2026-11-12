package com.pilot.core.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 在分配整行字符串及 JSON 树之前限制不受信任输入。 */
final class McpJson {
    static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;
    static final int MAX_CONFIG_BYTES = 1024 * 1024;
    private static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxDocumentLength(MAX_FRAME_BYTES)
            .maxStringLength(MAX_FRAME_BYTES).maxNameLength(4096).maxNumberLength(128).maxTokenCount(100_000).build())
        .build()).build();

    private McpJson() {
    }

    static byte[] readFrame(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(8192, limit));
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') {
                return bytes.toByteArray();
            }
            if (bytes.size() >= limit) {
                throw new McpException("MCP 消息超过原始字节大小上限（" + limit + " 字节），已关闭连接");
            }
            bytes.write(value);
        }
        if (bytes.size() != 0) {
            throw new McpException("MCP 输出在消息换行前结束，结果不完整");
        }
        return null;
    }

    static JsonNode parse(byte[] bytes) {
        try {
            return MAPPER.readTree(bytes);
        } catch (RuntimeException e) {
            throw new McpException("MCP JSON 无效或超过解析限制（深度、令牌数、字段长度）");
        }
    }

    static JsonNode readConfig(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_CONFIG_BYTES + 1);
            if (bytes.length > MAX_CONFIG_BYTES) {
                throw new McpException("MCP 配置超过大小上限（" + MAX_CONFIG_BYTES + " 字节）");
            }
            return parse(bytes);
        }
    }
}
