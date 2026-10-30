package com.pilot.core.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {
    private final ToolRegistry registry = new ToolRegistry();

    @Test
    void registersAndFindsToolsByName() {
        registry.register(tool("list_dir", "列目录"));
        registry.register(tool("read_file", "读文件"));

        assertTrue(registry.find("list_dir").isPresent());
        assertEquals("读文件", registry.find("read_file").orElseThrow().description());
        assertFalse(registry.find("search_text").isPresent());
        assertFalse(registry.find(null).isPresent());
    }

    @Test
    void rejectsDuplicateRegistration() {
        registry.register(tool("list_dir", "列目录"));
        assertThrows(IllegalArgumentException.class, () -> registry.register(tool("list_dir", "重复")));
    }

    @Test
    void exposesSortedDefinitions() {
        registry.register(tool("read_file", "读文件"));
        registry.register(tool("list_dir", "列目录"));

        List<String> names = registry.definitions().stream().map(definition -> definition.name()).toList();
        assertEquals(List.of("list_dir", "read_file"), names);
        assertEquals("列目录", registry.definitions().get(0).description());
    }

    @Test
    void exposesRegisteredNamesForPromptFiltering() {
        registry.register(tool("read_file", "读文件"));
        registry.register(tool("list_dir", "列目录"));

        assertEquals(java.util.Set.of("read_file", "list_dir"), registry.names());
    }

    @Test
    void unavailableToolsStayFindableButAreHiddenFromTheModel() {
        registry.register(tool("read_file", "读文件"));
        registry.register(tool("browser", "浏览器", Availability.unavailable("未检测到 Chrome")));

        assertTrue(registry.find("browser").isPresent());
        assertEquals(java.util.Set.of("read_file"), registry.names());
        assertEquals(List.of("read_file"), registry.definitions().stream().map(d -> d.name()).toList());
        assertEquals(Map.of("browser", "未检测到 Chrome"), registry.unavailable());
        assertEquals(List.of("read_file"), registry.availableTools().stream().map(Tool::name).toList());
    }

    @Test
    void availabilityIsReevaluatedOnEveryQuery() {
        java.util.concurrent.atomic.AtomicReference<Availability> state =
                new java.util.concurrent.atomic.AtomicReference<>(Availability.unavailable("starting"));
        registry.register(new Tool() {
            public String name() { return "browser"; }
            public String description() { return "b"; }
            public Map<String, Object> parameters() { return Map.of("type", "object"); }
            public boolean readOnly() { return true; }
            public Availability availability() { return state.get(); }
            public String execute(Map<String, Object> args) { return ""; }
        });
        assertTrue(registry.names().isEmpty());

        state.set(Availability.ready());

        assertEquals(java.util.Set.of("browser"), registry.names());
        assertTrue(registry.unavailable().isEmpty());
    }

    @Test
    void aThrowingAvailabilityProbeCountsAsUnavailable() {
        registry.register(new Tool() {
            public String name() { return "browser"; }
            public String description() { return "b"; }
            public Map<String, Object> parameters() { return Map.of("type", "object"); }
            public boolean readOnly() { return true; }
            public Availability availability() { throw new IllegalStateException("boom"); }
            public String execute(Map<String, Object> args) { return ""; }
        });

        assertTrue(registry.names().isEmpty());
        assertTrue(registry.unavailable().get("browser").contains("boom"));
    }

    private static Tool tool(String name, String description, Availability availability) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return description; }
            public Map<String, Object> parameters() { return Map.of("type", "object"); }
            public boolean readOnly() { return true; }
            public Availability availability() { return availability; }
            public String execute(Map<String, Object> args) { return ""; }
        };
    }

    private static Tool tool(String name, String description) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of("type", "object");
            }

            @Override
            public boolean readOnly() {
                return true;
            }

            @Override
            public String execute(Map<String, Object> args) {
                return "";
            }
        };
    }
}
