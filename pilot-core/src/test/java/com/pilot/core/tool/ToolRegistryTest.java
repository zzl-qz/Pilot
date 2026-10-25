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
