package com.pilot.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class DeferredToolRegistryTest {
    @Test
    void remembersImmutableSnapshotsAndForgetsSessions() {
        DeferredToolRegistry registry = new DeferredToolRegistry();
        List<String> names = new ArrayList<>(List.of("browser", "mcp"));

        registry.remember("session", names);
        names.add("later");
        List<String> snapshot = registry.snapshot("session");

        assertEquals(List.of("browser", "mcp"), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add("write"));
        registry.forget("session");
        assertEquals(null, registry.snapshot("session"));
    }
}
