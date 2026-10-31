package com.pilot.core.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertNotNull(CommandChain.parse("mvn test > log.txt").error());
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
        String command = String.join(" && ", java.util.Collections.nCopies(CommandChain.MAX_SEGMENTS + 1, "ls"));

        assertNotNull(CommandChain.parse(command).error());
    }

    @Test
    void quotesEmbeddedSingleQuotes() {
        assertEquals("'it'\\''s'", CommandChain.quote("it's"));
        assertEquals("'a b'", CommandChain.quote("a b"));
    }
}
