package com.pilot.server.frontend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FrontendDepsPromptTest {

    @Test
    void onlyExplicitYesIsAccepted() {
        assertEquals(FrontendDepsPrompt.YES, FrontendDepsPrompt.parseChoice("2"));
        assertEquals(FrontendDepsPrompt.YES, FrontendDepsPrompt.parseChoice(" 2 "));
        assertEquals(FrontendDepsPrompt.YES, FrontendDepsPrompt.parseChoice("Y"));
        assertEquals(FrontendDepsPrompt.YES, FrontendDepsPrompt.parseChoice("yes"));
        assertEquals(FrontendDepsPrompt.NO, FrontendDepsPrompt.parseChoice("1"));
        assertEquals(FrontendDepsPrompt.NO, FrontendDepsPrompt.parseChoice(""));
        assertEquals(FrontendDepsPrompt.NO, FrontendDepsPrompt.parseChoice(null));
        assertEquals(FrontendDepsPrompt.NO, FrontendDepsPrompt.parseChoice("随便"));
        // 管道重定向偶尔会在前面塞 BOM
        assertEquals(FrontendDepsPrompt.YES, FrontendDepsPrompt.parseChoice("\uFEFF2"));
    }

    @Test
    void enterConfirmsTypedChoice() {
        assertEquals(FrontendDepsPrompt.YES, read("2\n"));
        assertEquals(FrontendDepsPrompt.NO, read("\n"));
        assertEquals(FrontendDepsPrompt.NO, read("1\r\n"));
    }

    @Test
    void silentInputFallsBackToNoWithoutHoldingStartup() {
        long start = System.nanoTime();
        int choice = FrontendDepsPrompt.readChoice(new ByteArrayInputStream(new byte[0]), false, 0);
        assertEquals(FrontendDepsPrompt.NO, choice);
        assertTrue(System.nanoTime() - start < 2_000_000_000L, "超时不能把启动拖住");
    }

    @Test
    void runningFromClassesCountsAsDevelopment() {
        // surefire 下 code source 是目录，与 IDE / mvn 直接跑 main 的形态一致
        assertTrue(FrontendDepsPrompt.runningFromClasses());
    }

    private static int read(String text) {
        return FrontendDepsPrompt.readChoice(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), false, 1);
    }
}
