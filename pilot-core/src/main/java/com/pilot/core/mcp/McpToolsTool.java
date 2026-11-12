package com.pilot.core.mcp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.pilot.core.tool.Availability;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolSupport;

/**
 * 按需发现 MCP 工具：模型每次请求只携带 {@code mcp_tools} 与 {@code mcp_call} 两个定义，
 * 具体服务器、工具和参数结构在需要时才查询——避免把几十个远程工具的 schema 塞进每一轮上下文。
 *
 * <p>三种用法：无参数列出已配置的服务器；给 {@code server}（可加 {@code query} 过滤）列出它的工具；
 * 再给 {@code tool} 取该工具的完整参数 schema。查询未启动的服务器会启动它，等同于执行配置里的命令，
 * 因此这种调用需要审批；只看配置和已启动服务器的信息是只读的。
 */
public final class McpToolsTool implements Tool {
    private static final int MAX_LISTED = 80;

    private final McpManager manager;
    private final Path workspace;

    private record Checked(Map<String, Object> args, McpManager.Selection selection, boolean allowStart) {
    }

    private final ThreadLocal<Checked> checked = new ThreadLocal<>();
    private final ThreadLocal<Checked> approval = new ThreadLocal<>();

    public McpToolsTool(McpManager manager, Path workspace) {
        this.manager = manager;
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "mcp_tools";
    }

    @Override
    public String description() {
        return promptDescription();
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties",
            Map.of("server", Map.of("type", "string", "description", "MCP 服务器名；省略则列出所有已配置的服务器"), "query",
                Map.of("type", "string", "description", "按名称或描述过滤工具（不区分大小写）"), "tool",
                Map.of("type", "string", "description", "给出后返回该工具的完整说明与参数 schema")),
            "additionalProperties", false);
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    /** 只有查询「尚未启动的服务器」才需要审批，因为那会执行配置里的命令。 */
    @Override
    public boolean readOnly(Map<String, Object> args) {
        String server = ToolSupport.stringArg(args, "server");
        if (server == null) {
            checked.remove();
            return true;
        }
        McpManager.Selection selected = manager.selection(workspace, server);
        boolean readOnly = selected.config() == null || selected.client() != null && selected.client().running()
            && selected.client().matches(selected.config(), selected.extraPath());
        checked.set(new Checked(args, selected, !readOnly));
        return readOnly;
    }

    @Override
    public String approvalDescription(Map<String, Object> args) {
        String server = ToolSupport.stringArg(args, "server");
        if (server == null) {
            return null;
        }
        Checked current = checked.get();
        if (current == null || current.args() != args) {
            current = new Checked(args, manager.selection(workspace, server), true);
        }
        approval.set(current);
        McpServerConfig config = current.selection().config();
        return config == null ? "MCP 服务器配置不存在" : "发现 MCP 服务器 " + server + "；必要时启动命令：" + config.command() + " "
            + String.join(" ", config.args()) + "；环境变量名：" + config.env().keySet();
    }

    @Override
    public boolean parallelSafe() {
        return false;
    }

    @Override
    public Availability availability() {
        return manager.hasServers(workspace) ? Availability.ready()
            : Availability.unavailable("当前工作区没有配置 MCP 服务器（.mcp.json）");
    }

    @Override
    public String execute(Map<String, Object> args) {
        String server = ToolSupport.stringArg(args, "server");
        String tool = ToolSupport.stringArg(args, "tool");
        String query = ToolSupport.stringArg(args, "query");
        if (server == null) {
            if (tool != null || query != null) {
                return "ERROR: 查询工具需要同时给出 server；先不带参数列出服务器";
            }
            return listServers();
        }
        List<McpToolInfo> tools;
        try {
            Checked inspected = checked.get();
            Checked approved = approval.get();
            checked.remove();
            approval.remove();
            Checked guard = approved != null && approved.args() == args ? approved
                : inspected != null && inspected.args() == args ? inspected : null;
            tools = guard == null ? manager.tools(workspace, server)
                : manager.tools(workspace, server, guard.selection(), guard.allowStart());
        } catch (McpException e) {
            return "ERROR: " + e.getMessage();
        }
        if (tool != null) {
            return tools.stream().filter(info -> info.name().equals(tool)).findFirst()
                .map(info -> describe(server, info))
                .orElse("ERROR: 服务器 " + server + " 没有工具 " + tool + "；可用：" + names(tools));
        }
        List<McpToolInfo> matched = tools;
        if (query != null) {
            String needle = query.toLowerCase(Locale.ROOT);
            matched = tools.stream().filter(info -> info.name().toLowerCase(Locale.ROOT).contains(needle)
                || info.description().toLowerCase(Locale.ROOT).contains(needle)).toList();
        }
        if (matched.isEmpty()) {
            return "服务器 " + server + " 没有匹配的工具" + (query == null ? "。" : "（共 " + tools.size() + " 个，换个关键词）。");
        }
        StringBuilder out =
            new StringBuilder("服务器 ").append(server).append(" 的工具（").append(matched.size()).append(" 个");
        if (matched.size() > MAX_LISTED) {
            out.append("，仅显示前 ").append(MAX_LISTED).append(" 个，请用 query 缩小");
        }
        out.append("）：\n");
        McpPermissionPolicy policy = manager.permissionPolicy(workspace, server);
        for (McpToolInfo info : matched.stream().limit(MAX_LISTED).toList()) {
            out.append("- ").append(info.name()).append(permissionLabel(info, policy));
            if (!info.description().isBlank()) {
                out.append("：").append(ToolSupport.oneLine(info.description(), 140));
            }
            out.append('\n');
        }
        out.append("用 mcp_tools(server=\"").append(server).append("\", tool=\"名字\") 查看参数，再用 mcp_call 调用。");
        return out.toString();
    }

    private String listServers() {
        Map<String, McpServerConfig> servers = manager.servers(workspace);
        if (servers.isEmpty()) {
            return "当前工作区没有配置 MCP 服务器（.mcp.json）。";
        }
        List<String> lines = new ArrayList<>();
        for (McpServerConfig config : servers.values()) {
            String status;
            if (!config.usable()) {
                status = "不可用：" + (config.unsupported() == null ? "缺少 command" : config.unsupported());
            } else if (manager.configurationChanged(workspace, config.name())) {
                status = "配置已变更，重启 Pilot 服务后生效；旧连接不再接受新调用";
            } else if (manager.running(workspace, config.name())) {
                status = "已启动，" + manager.knownTools(workspace, config.name()).size() + " 个工具";
            } else if (manager.failure(workspace, config.name()) != null) {
                status = "上次启动失败：" + manager.failure(workspace, config.name());
            } else {
                status = "未启动（查询它的工具时才会启动）";
            }
            lines.add("- " + config.name() + "：" + status);
        }
        return "已配置的 MCP 服务器：\n" + String.join("\n", lines);
    }

    private String describe(String server, McpToolInfo info) {
        return "工具 " + server + "." + info.name() + permissionLabel(info, manager.permissionPolicy(workspace, server))
            + "\n" + "只读策略仅用于 Pilot 权限判断，不等于操作系统隔离。\n" + (info.description().isBlank() ? "" : info.description() + "\n")
            + "参数 schema：\n" + info.inputSchema();
    }

    private static String permissionLabel(McpToolInfo info, McpPermissionPolicy policy) {
        if (policy.requireApprovalTools().contains(info.name())) {
            return " [本地策略要求明确批准]";
        }
        if (policy.readOnly(info)) {
            return " [本地策略只读]";
        }
        return info.readOnlyHint() ? " [服务器声明只读；未获本地信任]" : "";
    }

    private static String names(List<McpToolInfo> tools) {
        return tools.stream().map(McpToolInfo::name).limit(40).reduce((a, b) -> a + ", " + b).orElse("(无)");
    }
}
