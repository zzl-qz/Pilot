package com.pilot.server.multi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubagentCheckoutsTest {
    @TempDir
    Path root;

    @Test
    void writesStayInTheCheckoutAndCanBeRemoved() throws Exception {
        Path parent = Files.createDirectories(root.resolve("parent"));
        AtomicInteger removed = new AtomicInteger();
        SubagentCheckouts checkouts = new SubagentCheckouts(workspace -> {
            try {
                Path checkout = Files.createDirectories(root.resolve("checkout"));
                Files.writeString(checkout.resolve("written.txt"), "child");
                return new SubagentCheckouts.Opened(checkout, "written.txt", "");
            } catch (Exception e) {
                return new SubagentCheckouts.Opened(null, "", e.getMessage());
            }
        });

        SubagentCheckouts.Opened opened = checkouts.acquire(parent);

        assertTrue(opened.ok());
        assertTrue(Files.exists(opened.path().resolve("written.txt")));
        assertFalse(Files.exists(parent.resolve("written.txt")));
        assertEquals("written.txt", opened.diff());
        checkouts.release(opened.path());
        removed.incrementAndGet();
        assertEquals(0, checkouts.openCount());
        assertEquals(1, removed.get());
    }

    @Test
    void fifthCheckoutIsRefused() {
        SubagentCheckouts checkouts = new SubagentCheckouts(workspace -> new SubagentCheckouts.Opened(root.resolve("c"), "", ""));
        for (int i = 0; i < 4; i++) {
            assertTrue(checkouts.acquire(root).ok());
        }
        SubagentCheckouts.Opened fifth = checkouts.acquire(root);
        assertFalse(fifth.ok());
        assertTrue(fifth.error().contains("4"));
    }

    @Test
    void creationFailureDoesNotOpenAPath() {
        SubagentCheckouts checkouts = new SubagentCheckouts(workspace -> new SubagentCheckouts.Opened(null, "", "磁盘满了"));
        SubagentCheckouts.Opened opened = checkouts.acquire(root);
        assertFalse(opened.ok());
        assertTrue(opened.error().contains("未修改父工作区"));
        assertEquals(0, checkouts.openCount());
    }
}
