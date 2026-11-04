package com.pilot.mobile

import org.junit.Assert.*
import org.junit.Test

class MessageBodyTest {
    @Test fun separatesHeadingsParagraphsAndCodeWithoutDroppingText() {
        val blocks = messageBlocks("## 验证结果\n\n正常段落\n继续说明\n\n```java\nString name = \"中文\";\n```\n结尾")
        assertEquals(4, blocks.size)
        assertTrue(blocks[0].heading)
        assertEquals("验证结果", blocks[0].text)
        assertEquals("正常段落\n继续说明", blocks[1].text)
        assertTrue(blocks[2].code)
        assertEquals("java", blocks[2].language)
        assertEquals("String name = \"中文\";", blocks[2].text)
        assertEquals("结尾", blocks[3].text)
    }

    @Test fun unfinishedStreamKeepsCodeAndBlankLines() {
        val code = "function verify() {\n\n  return `value`;"
        val blocks = messageBlocks("正在生成\n```js\n$code")
        assertEquals(2, blocks.size)
        assertEquals(code, blocks[1].text)
        assertTrue(blocks[1].code)
    }

    @Test fun fencedContentDoesNotInterpretMarkdownOrNestedFenceAsFormatting() {
        val raw = "# title\n**literal**\n```not-a-closing-fence\n    indentation"
        assertEquals(raw, messageBlocks("```text\n$raw\n```").single().text)
    }

    @Test fun inlineFormattingLeavesUnmatchedDelimitersAndFilePathsReadable() {
        assertEquals("先验证 src/Main.kt，再提交。", messageInline("先**验证** `src/Main.kt`，再提交。").text)
        assertEquals("未完成 **加粗 与 `代码", messageInline("未完成 **加粗 与 `代码").text)
        assertEquals("C:\\项目\\file.txt", messageInline("C:\\项目\\file.txt").text)
    }

    @Test fun emptyAndUnformattedTextRemainSafe() {
        assertTrue(messageBlocks("").isEmpty())
        assertEquals("- 第一步\n- 第二步", messageBlocks("- 第一步\n- 第二步").single().text)
        assertEquals("#没有空格", messageBlocks("#没有空格").single().text)
    }
}
