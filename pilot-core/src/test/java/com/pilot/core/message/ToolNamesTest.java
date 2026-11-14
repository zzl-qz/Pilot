package com.pilot.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;

import org.junit.jupiter.api.Test;

/** 工具名已经写进历史会话和用户的权限配置，常量的值一个字都不能变。 */
class ToolNamesTest {

    @Test
    void namesStayStable() {
        assertEquals("read_file", ToolNames.READ_FILE);
        assertEquals("list_dir", ToolNames.LIST_DIR);
        assertEquals("glob_files", ToolNames.GLOB_FILES);
        assertEquals("search_text", ToolNames.SEARCH_TEXT);
        assertEquals("edit_file", ToolNames.EDIT_FILE);
        assertEquals("create_file", ToolNames.CREATE_FILE);
        assertEquals("apply_patch", ToolNames.APPLY_PATCH);
        assertEquals("run_command", ToolNames.RUN_COMMAND);
        assertEquals("command_status", ToolNames.COMMAND_STATUS);
        assertEquals("command_logs", ToolNames.COMMAND_LOGS);
        assertEquals("stop_command", ToolNames.STOP_COMMAND);
        assertEquals("web_search", ToolNames.WEB_SEARCH);
        assertEquals("web_fetch", ToolNames.WEB_FETCH);
        assertEquals("update_plan", ToolNames.UPDATE_PLAN);
        assertEquals("use_skill", ToolNames.USE_SKILL);
        assertEquals("read_tool_output", ToolNames.READ_TOOL_OUTPUT);
        assertEquals("browser", ToolNames.BROWSER);
        assertEquals("spawn_agent", ToolNames.SPAWN_AGENT);
        assertEquals("dispatch_agents", ToolNames.DISPATCH_AGENTS);
        assertEquals("promote_goal", ToolNames.PROMOTE_GOAL);
        assertEquals("record_research", ToolNames.RECORD_RESEARCH);
        assertEquals("wait_until", ToolNames.WAIT_UNTIL);
    }

    @Test
    void fileEditsCoverExactlyTheThreeWritingTools() {
        assertEquals(Set.of("edit_file", "create_file", "apply_patch"), ToolNames.FILE_EDITS);
    }
}
