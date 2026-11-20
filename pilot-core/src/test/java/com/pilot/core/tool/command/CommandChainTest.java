package com.pilot.core.tool.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

class CommandChainTest {
    @Test
    void splitsOnOperatorsOutsideQuotes() {
        CommandChain.Parsed parsed = CommandChain.parse("git diff --stat | head -20 && echo 'a | b && c' ; ls");

        assertNull(parsed.error());
        assertEquals(4, parsed.segments().size());
        assertEquals(List.of("|", "&&", ";"), parsed.operators());
        assertEquals(List.of("echo", "a | b && c"), parsed.segments().get(2).tokens());
    }

    @Test
    void quotedMetaCharactersAreLiterals() {
        CommandChain.Parsed parsed = CommandChain.parse("git commit -m \"fix: a & b > c `d` $(e) (f)\"");

        assertNull(parsed.error());
        assertFalse(parsed.chained());
        assertEquals("fix: a & b > c `d` $(e) (f)", parsed.segments().getFirst().tokens().get(3));
    }

    @Test
    void allowsMergingStderrOnly() {
        CommandChain.Parsed parsed = CommandChain.parse("mvn -q test 2>&1 | tail -5");

        assertNull(parsed.error());
        assertTrue(parsed.segments().getFirst().mergeStderr());
        assertEquals(List.of("mvn", "-q", "test"), parsed.segments().getFirst().tokens());
        CommandChain.Parsed redirected = CommandChain.parse("mvn test > log.txt");
        assertNull(redirected.error());
        assertEquals("log.txt", redirected.segments().getFirst().redirectTo());
        assertFalse(redirected.segments().getFirst().append());
        CommandChain.Parsed appended = CommandChain.parse("mvn test >> log.txt");
        assertNull(appended.error());
        assertTrue(appended.segments().getFirst().append());
        assertNotNull(CommandChain.parse("mvn test 2> log.txt").error());
        assertNotNull(CommandChain.parse("cat < in.txt").error());
    }

    @Test
    void rejectsShellSyntaxThatWouldBeReinterpreted() {
        for (String command : List.of("a `b`", "a $(b)", "a &", "a\nb", "a ||", "&& a", "a && && b",
            "echo 'unterminated", "")) {
            assertNotNull(CommandChain.parse(command).error(), command);
        }
        assertNull(CommandChain.parse("ls ;").error());
    }

    @Test
    void limitsChainLength() {
        String command = String.join(" && ", Collections.nCopies(CommandChain.MAX_SEGMENTS + 1, "ls"));

        assertNotNull(CommandChain.parse(command).error());
    }

    @Test
    void quotesEmbeddedSingleQuotes() {
        assertEquals("'it'\\''s'", CommandChain.quote("it's"));
        assertEquals("'a b'", CommandChain.quote("a b"));
    }

    @Test
    void handlesEscapedQuotesAndBackslashesInsideDoubleQuotesOnly() {
        // 真实复现场景：grep BRE 正则中同时含转义双引号 \" 与分支 \|，不能提前闭合引号或误拆分管道
        CommandChain.Parsed grep =
            CommandChain.parse("grep -l \"failures=\\\"[1-9]\\|errors=\\\"[1-9]\" target/surefire-reports/*.xml");
        assertNull(grep.error());
        assertFalse(grep.chained());
        assertEquals(List.of("grep", "-l", "failures=\"[1-9]\\|errors=\"[1-9]", "target/surefire-reports/*.xml"),
            grep.segments().getFirst().tokens());

        // Windows 路径与普通正则反斜杠原样保留，仅 \" 与 \\ 生效；末尾 \\ 不误吞闭合引号
        CommandChain.Parsed winAndEscapes =
            CommandChain.parse("echo \"C:\\Users\\alice\\file.txt\" \"dir\\\\\" \"a\\\\\\\"b\"");
        assertNull(winAndEscapes.error());
        assertEquals(List.of("echo", "C:\\Users\\alice\\file.txt", "dir\\", "a\\\"b"),
            winAndEscapes.segments().getFirst().tokens());

        // 单引号内所有字符严格字面量，不解析反斜杠转义
        CommandChain.Parsed single = CommandChain.parse("echo 'a \\\" b \\\\ c'");
        assertNull(single.error());
        assertEquals(List.of("echo", "a \\\" b \\\\ c"), single.segments().getFirst().tokens());

        // 重定向目标的双引号转义同样生效
        CommandChain.Parsed redirect = CommandChain.parse("echo hi > \"out \\\"1\\\".txt\"");
        assertNull(redirect.error());
        assertEquals("out \"1\".txt", redirect.segments().getFirst().redirectTo());

        // 末尾只有转义双引号 \" 时引号仍未闭合，必须报错
        assertNotNull(CommandChain.parse("echo \"unterminated \\\"").error());
    }
}
