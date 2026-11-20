package com.pilot.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.pilot.core.model.ToolDefinition;

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
    void enablingADeferredToolOnlyAppendsItAfterTheCoreTools() {
        registry.register(tool("apply_patch", "改文件"));
        registry.register(tool("browser", "浏览器"));
        registry.register(tool("edit_file", "编辑"));
        registry.register(tool("read_file", "读文件"));
        registry.register(tool("run_command", "跑命令"));
        List<ToolDefinition> core = registry.definitions();
        assertEquals(List.of("apply_patch", "edit_file", "read_file", "run_command"),
            core.stream().map(ToolDefinition::name).toList());

        registry.enable(List.of("browser"));

        List<ToolDefinition> expanded = registry.definitions();
        assertEquals(List.of("apply_patch", "edit_file", "read_file", "run_command", "browser"),
            expanded.stream().map(ToolDefinition::name).toList());
        // 开启 browser 不改动核心工具的位置与内容，前缀缓存才不会被整体作废
        assertEquals(core, expanded.subList(0, core.size()));
    }

    @Test
    void enabledDeferredToolsKeepTheirActivationOrder() {
        registry.register(tool("read_file", "读文件"));
        registry.register(tool("browser", "浏览器"));
        registry.register(tool("office", "办公文档"));

        registry.enable(List.of("office", "browser"));
        assertEquals(List.of("read_file", "office", "browser"),
            registry.definitions().stream().map(ToolDefinition::name).toList());
        assertEquals(List.of("office", "browser"), registry.enabledDeferredTools());

        assertEquals(List.of("office"), registry.enable(List.of("office")), "重复启用仍要告知模型已经可用");
        assertEquals(List.of("read_file", "office", "browser"),
            registry.definitions().stream().map(ToolDefinition::name).toList());
        assertEquals(List.of("office", "browser"), registry.enabledDeferredTools(), "重复启用不能改变激活顺序");
    }

    @Test
    void exposesRegisteredNamesForPromptFiltering() {
        registry.register(tool("read_file", "读文件"));
        registry.register(tool("list_dir", "列目录"));

        assertEquals(Set.of("read_file", "list_dir"), registry.names());
    }

    @Test
    void unavailableToolsStayFindableButAreHiddenFromTheModel() {
        registry.register(tool("read_file", "读文件"));
        registry.register(tool("browser", "浏览器", Availability.unavailable("未检测到 Chrome")));

        assertTrue(registry.find("browser").isPresent());
        assertEquals(Set.of("read_file"), registry.names());
        assertEquals(List.of("read_file"), registry.definitions().stream().map(d -> d.name()).toList());
        assertEquals(Map.of("browser", "未检测到 Chrome"), registry.unavailable());
        assertEquals(List.of("read_file"), registry.availableTools().stream().map(Tool::name).toList());
    }

    @Test
    void availabilityIsReevaluatedOnEveryQuery() {
        AtomicReference<Availability> state = new AtomicReference<>(Availability.unavailable("starting"));
        registry.register(new Tool() {
            public String name() {
                return "browser";
            }

            public String description() {
                return "b";
            }

            public Map<String, Object> parameters() {
                return Map.of("type", "object");
            }

            public boolean readOnly() {
                return true;
            }

            public Availability availability() {
                return state.get();
            }

            public String execute(Map<String, Object> args) {
                return "";
            }
        });
        assertTrue(registry.names().isEmpty());

        state.set(Availability.ready());
        registry.enable(List.of("browser"));

        assertEquals(Set.of("browser"), registry.names());
        assertTrue(registry.unavailable().isEmpty());
    }

    @Test
    void aThrowingAvailabilityProbeCountsAsUnavailable() {
        registry.register(new Tool() {
            public String name() {
                return "browser";
            }

            public String description() {
                return "b";
            }

            public Map<String, Object> parameters() {
                return Map.of("type", "object");
            }

            public boolean readOnly() {
                return true;
            }

            public Availability availability() {
                throw new IllegalStateException("boom");
            }

            public String execute(Map<String, Object> args) {
                return "";
            }
        });

        assertTrue(registry.names().isEmpty());
        assertTrue(registry.unavailable().get("browser").contains("boom"));
    }

    private static Tool tool(String name, String description, Availability availability) {
        return new Tool() {
            public String name() {
                return name;
            }

            public String description() {
                return description;
            }

            public Map<String, Object> parameters() {
                return Map.of("type", "object");
            }

            public boolean readOnly() {
                return true;
            }

            public Availability availability() {
                return availability;
            }

            public String execute(Map<String, Object> args) {
                return "";
            }
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
