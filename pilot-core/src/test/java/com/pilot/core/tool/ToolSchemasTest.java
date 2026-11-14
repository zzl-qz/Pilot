package com.pilot.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 生成的参数说明要和原来手写的 Map.of 完全一样，模型看到的工具定义不能变。 */
class ToolSchemasTest {

    @Test
    void scalarsCarryTypeAndDescription() {
        assertEquals(Map.of("type", "string", "description", "文件路径"), ToolSchemas.string("文件路径"));
        assertEquals(Map.of("type", "integer", "description", "最多条数"), ToolSchemas.integer("最多条数"));
        assertEquals(Map.of("type", "boolean", "description", "是否递归"), ToolSchemas.bool("是否递归"));
    }

    @Test
    void objectListsPropertiesRequiredAndRejectsExtras() {
        Map<String, Object> properties = Map.of("path", ToolSchemas.string("文件路径"));
        assertEquals(Map.of("type", "object", "properties", properties, "required", List.of("path"),
            "additionalProperties", false), ToolSchemas.object(properties, List.of("path")));
    }

    @Test
    void realToolSchemaKeepsItsShape() {
        Map<String, Object> schema = new ListDirTool(null).parameters();
        assertEquals("object", schema.get("type"));
        assertEquals(false, schema.get("additionalProperties"));
        assertEquals(
            Map.of("type", "string", "description",
                ((Map<?, ?>)((Map<?, ?>)schema.get("properties")).get("path")).get("description")),
            ((Map<?, ?>)schema.get("properties")).get("path"));
    }
}
