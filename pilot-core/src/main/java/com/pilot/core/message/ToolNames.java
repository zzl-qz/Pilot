package com.pilot.core.message;

import java.util.Set;

/**
 * 工具的名字。
 * 模型按这些名字调用工具，权限规则、任务证据、提示词分段也按名字匹配，
 * 所以每个名字只在这里写一次，别处一律引用常量。
 * 这些值已经写进历史会话和用户的权限配置，不能改。
 * 只收录了被多处引用的名字；只在自己类里出现一次的工具名留在原处。
 */
public final class ToolNames {
    public static final String READ_FILE = "read_file";
    public static final String LIST_DIR = "list_dir";
    public static final String GLOB_FILES = "glob_files";
    public static final String SEARCH_TEXT = "search_text";

    public static final String EDIT_FILE = "edit_file";
    public static final String CREATE_FILE = "create_file";
    public static final String APPLY_PATCH = "apply_patch";

    public static final String RUN_COMMAND = "run_command";
    public static final String COMMAND_STATUS = "command_status";
    public static final String COMMAND_LOGS = "command_logs";
    public static final String STOP_COMMAND = "stop_command";

    public static final String WEB_SEARCH = "web_search";
    public static final String WEB_FETCH = "web_fetch";

    public static final String UPDATE_PLAN = "update_plan";
    public static final String USE_SKILL = "use_skill";
    public static final String READ_TOOL_OUTPUT = "read_tool_output";
    public static final String BROWSER = "browser";

    /** 以下几个工具由 pilot-server 注册，core 的提示词分段和上下文统计也要认它们。 */
    public static final String SPAWN_AGENT = "spawn_agent";
    public static final String DISPATCH_AGENTS = "dispatch_agents";
    public static final String PROMOTE_GOAL = "promote_goal";
    public static final String RECORD_RESEARCH = "record_research";
    public static final String WAIT_UNTIL = "wait_until";

    /** 会直接改工作区文件的工具。 */
    public static final Set<String> FILE_EDITS = Set.of(EDIT_FILE, CREATE_FILE, APPLY_PATCH);

    private ToolNames() {
    }
}
