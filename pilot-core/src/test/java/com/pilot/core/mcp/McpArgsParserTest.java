package com.pilot.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 双格式 args 解析：简写（含引号）与 JSON 数组等价，坏输入给带示例的错误。 */
class McpArgsParserTest {

    @Test
    void blankInputYieldsNoArgs() {
        assertEquals(List.of(), McpArgsParser.parse(null));
        assertEquals(List.of(), McpArgsParser.parse(""));
        assertEquals(List.of(), McpArgsParser.parse("   "));
    }

    @Test
    void plainSplittingKeepsEmptyArguments() {
        assertEquals(List.of("-y", "@scope/server", "--port", "3000"),
            McpArgsParser.parse("  -y  @scope/server --port 3000 "));
    }

    @Test
    void quotedSegmentsKeepSpacesAndBothQuoteStylesWork() {
        assertEquals(List.of("-y", "@scope/server", "D:\\my dir", "second 'part'"),
            McpArgsParser.parse("-y @scope/server \"D:\\my dir\" \"second 'part'\""));
        // 双引号包单引号、单引号包双引号都支持
        assertEquals(List.of("say \"hi\" now"), McpArgsParser.parse("'say \"hi\" now'"));
        assertEquals(List.of("it's here"), McpArgsParser.parse("\"it's here\""));
    }

    @Test
    void emptyQuotesProduceEmptyArgument() {
        assertEquals(List.of("a", "", "b"), McpArgsParser.parse("a \"\" b"));
    }

    @Test
    void unbalancedQuoteIsRejectedWithExample() {
        McpException error = assertThrows(McpException.class, () -> McpArgsParser.parse("a \"unclosed"));
        assertTrue(error.getMessage().contains("引号没有成对"), error.getMessage());
        assertTrue(error.getMessage().contains("示例"), error.getMessage());
    }

    @Test
    void jsonArrayFormatEqualsPlainSplitting() {
        assertEquals(McpArgsParser.parse("-y @scope/server \"D:\\my dir\""),
            McpArgsParser.parse("[\"-y\", \"@scope/server\", \"D:\\\\my dir\"]"));
    }

    @Test
    void malformedJsonArrayIsRejectedWithGuidance() {
        McpException broken = assertThrows(McpException.class, () -> McpArgsParser.parse("[\"-y\", "));
        assertTrue(broken.getMessage().contains("JSON"), broken.getMessage());

        // JSON 对象也会被识别为「想用 JSON 格式」并引导为数组，不会被当成一整个参数
        McpException notArray = assertThrows(McpException.class, () -> McpArgsParser.parse("{\"a\":1}"));
        assertTrue(notArray.getMessage().contains("字符串数组"), notArray.getMessage());

        McpException nonString = assertThrows(McpException.class, () -> McpArgsParser.parse("[\"-y\", 300]"));
        assertTrue(nonString.getMessage().contains("字符串"), nonString.getMessage());
    }
    @Test
    void jsonEmptyArrayIsAccepted() {
        assertEquals(List.of(), McpArgsParser.parse("[]"));
    }
}
