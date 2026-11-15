package com.pilot.server.maintenance;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.pilot.core.hook.Hooks;
import com.pilot.core.mcp.McpManager;
import com.pilot.core.mcp.McpServerConfig;
import com.pilot.core.mcp.McpToolInfo;
import com.pilot.core.mcp.McpTrustStore;
import com.pilot.core.message.ToolNames;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.core.skill.SkillCatalog;
import com.pilot.core.skill.SkillManifest;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.skill.SkillService;
import com.pilot.server.tool.WorkspaceToolFactory;

import lombok.RequiredArgsConstructor;

/**
 * 升级前的能力体检：列出这次变化会打掉、降级或保持不动的插件。
 *
 * <p>已有的哈希钉住只解决「变了要不要重新审批」，回答不了「升级后还能不能用」。
 * 这里把 MCP 服务器、技能、钩子放在同一张表里，用 ok / changed / broken / unknown 四档说明后果，
 * 让用户在升级之前就知道会发生什么，而不是升级之后逐个排查。
 *
 * <p>整个检查是只读的：不启动 MCP 服务器、不执行钩子、不写任何配置。
 */
@Service
@RequiredArgsConstructor
public class PluginCompatService {
    public static final String OK = "ok";
    public static final String CHANGED = "changed";
    public static final String BROKEN = "broken";
    public static final String UNKNOWN = "unknown";

    /** @param details 供界面展开的具体条目，例如「工具 X 已不在服务器声明的清单里」 */
    public record Item(String kind, String name, String source, String status, String reason, List<String> details) {
        public Item {
            details = details == null ? List.of() : List.copyOf(details);
        }
    }

    public record Counts(int ok, int changed, int broken, int unknown) {
    }

    public record Report(Instant generatedAt, String workspace, Counts counts, List<Item> items, String markdown) {
    }

    private final SessionService sessions;
    private final WorkspaceToolFactory toolFactory;
    private final SkillService skills;
    private final AgentProperties agentProperties;
    private final ObjectProvider<McpManager> mcpManager;

    /** 检查某个会话所在工作区的插件兼容情况。 */
    public Report inspect(String sessionId) {
        Path workspace = workspaceOf(sessionId);
        List<Item> items = new ArrayList<>();
        items.addAll(mcpItems(workspace));
        items.addAll(skillItems(workspace, currentToolNames(workspace)));
        items.addAll(hookItems(workspace, currentToolNames(workspace)));
        items.sort(Comparator.comparing(Item::kind).thenComparing(Item::name));
        return new Report(Instant.now(), workspace.toString(), counts(items), List.copyOf(items), markdown(items));
    }

    // ————— MCP —————

    private List<Item> mcpItems(Path workspace) {
        McpManager manager = mcpManager.getIfAvailable();
        if (manager == null || manager.trustStore() == null) {
            return List.of();
        }
        Map<String, McpServerConfig> servers = manager.servers(workspace);
        McpTrustStore trustStore = manager.trustStore();
        List<Item> items = new ArrayList<>();
        for (McpServerConfig config : servers.values()) {
            String name = config.name();
            McpTrustStore.ServerTrust trust = trustStore.record(workspace, name).orElse(null);
            if (trust == null) {
                items.add(new Item("mcp", name, "workspace", OK, "尚未信任；首次启动会请求批准，不会被升级影响", List.of()));
                continue;
            }
            if (!trust.configHash().equals(McpTrustStore.hash(config))) {
                items.add(new Item("mcp", name, "workspace", CHANGED, "启动命令与已信任的版本不同；下次启动会重新请求批准", List.of()));
                continue;
            }
            if (trust.tools().isEmpty()) {
                items.add(new Item("mcp", name, "workspace", UNKNOWN, "已信任但还没有观测过工具清单；下一次启动才会钉住", List.of()));
                continue;
            }
            if (!manager.running(workspace, name)) {
                items.add(new Item("mcp", name, "workspace", UNKNOWN, "服务器当前未运行，无法比对工具定义", List.of()));
                continue;
            }
            List<McpToolInfo> observed = manager.knownTools(workspace, name);
            List<String> details = new ArrayList<>();
            boolean changed = false;
            boolean broken = false;
            Set<String> observedNames = new HashSet<>();
            for (McpToolInfo tool : observed) {
                observedNames.add(tool.name());
                McpTrustStore.ToolPin pin = trust.tools().get(tool.name());
                if (pin == null) {
                    details.add("新增工具 " + tool.name() + "：未钉住，首次调用会请求批准");
                    changed = true;
                } else if (!pin.hash().equals(McpTrustStore.hash(tool))) {
                    details.add("工具定义变化 " + tool.name() + "：需要重新批准");
                    changed = true;
                }
            }
            for (String pinned : trust.tools().keySet()) {
                if (!observedNames.contains(pinned)) {
                    details.add("已钉住的工具 " + pinned + " 不再被服务器声明");
                    broken = true;
                }
            }
            String status = broken ? BROKEN : changed ? CHANGED : OK;
            String reason = broken ? "有已钉住的工具消失，依赖它的调用会失败" : changed ? "工具定义有变化，相关调用会退回逐次审批" : "与已信任记录一致";
            items.add(new Item("mcp", name, "workspace", status, reason, details));
        }
        return items;
    }

    // ————— 技能 —————

    private List<Item> skillItems(Path workspace, Set<String> toolNames) {
        if (skills == null || !skills.enabled()) {
            return List.of();
        }
        SkillCatalog catalog = skills.catalogFor(workspace.toString());
        List<Item> items = new ArrayList<>();
        for (SkillManifest manifest : catalog.list()) {
            List<String> missing = manifest.allowedTools() == null ? List.of()
                : manifest.allowedTools().stream().filter(tool -> !matchesTool(toolNames, tool)).toList();
            List<String> details = missing.stream().map(tool -> "声明的工具 " + tool + " 当前不可用").toList();
            items.add(new Item("skill", manifest.name(),
                manifest.source() == null ? "" : manifest.source().name().toLowerCase(Locale.ROOT),
                missing.isEmpty() ? OK : CHANGED, missing.isEmpty() ? "无需变更" : "技能声明的部分工具当前不存在；技能本身仍是提示词，不会因此失效",
                details));
        }
        return items;
    }

    // ————— 钩子 —————

    private List<Item> hookItems(Path workspace, Set<String> toolNames) {
        List<Item> items = new ArrayList<>();
        for (Hooks.Command command : Hooks.load(workspace).commands()) {
            Hooks.Spec spec = command.spec();
            boolean matched = toolNames.stream().anyMatch(spec::matches);
            List<String> details = new ArrayList<>();
            if (!matched) {
                details.add("matcher「" + spec.matcher() + "」没有命中任何已注册工具");
            }
            items.add(new Item("hook", label(spec), command.source() == null ? "" : command.source().toString(),
                matched ? OK : BROKEN, matched ? "钩子仍会触发" : "matcher 指向的工具不存在，这条钩子不会再执行；升级后不会报错，只会静默不生效", details));
        }
        return items;
    }

    private static String label(Hooks.Spec spec) {
        String command = spec.command() == null ? "" : spec.command().strip();
        String shortened = command.length() > 60 ? command.substring(0, 60) + "…" : command;
        return spec.matcher() + " → " + shortened;
    }

    // ————— 公共 —————

    private Set<String> currentToolNames(Path workspace) {
        try {
            return new HashSet<>(toolFactory.registryFor(workspace.toString()).names());
        } catch (RuntimeException e) {
            return Set.of(ToolNames.READ_FILE, ToolNames.LIST_DIR, ToolNames.GLOB_FILES, ToolNames.SEARCH_TEXT,
                ToolNames.EDIT_FILE, ToolNames.CREATE_FILE, ToolNames.APPLY_PATCH, ToolNames.RUN_COMMAND,
                ToolNames.WEB_SEARCH, ToolNames.WEB_FETCH, ToolNames.UPDATE_PLAN);
        }
    }

    /** 允许技能/钩子写通配或前缀形式；只要任一已注册工具命中就算仍然有效。 */
    private static boolean matchesTool(Set<String> toolNames, String declared) {
        if (declared == null || declared.isBlank()) {
            return true;
        }
        return toolNames.stream().anyMatch(name -> name.equalsIgnoreCase(declared.strip()));
    }

    private Path workspaceOf(String sessionId) {
        Session session = sessionId == null ? null : sessions.find(sessionId).orElse(null);
        String configured = session == null ? null : session.workspace();
        String target = configured == null || configured.isBlank() ? agentProperties.workspace() : configured;
        return toolFactory.resolve(target);
    }

    private static Counts counts(List<Item> items) {
        Map<String, Integer> tally = new TreeMap<>();
        for (Item item : items) {
            tally.merge(item.status(), 1, Integer::sum);
        }
        return new Counts(tally.getOrDefault(OK, 0), tally.getOrDefault(CHANGED, 0), tally.getOrDefault(BROKEN, 0),
            tally.getOrDefault(UNKNOWN, 0));
    }

    /** 可直接粘贴到升级记录里的报告；与接口返回同源，不另写一版判断。 */
    private static String markdown(List<Item> items) {
        StringBuilder out = new StringBuilder("| 类型 | 名称 | 状态 | 说明 |\n| --- | --- | --- | --- |\n");
        for (Item item : items) {
            out.append("| ").append(item.kind()).append(" | ").append(escapeCell(item.name())).append(" | ")
                .append(item.status()).append(" | ").append(escapeCell(item.reason())).append(" |\n");
        }
        if (items.isEmpty()) {
            out.append("\n当前工作区没有需要检查的插件。\n");
        }
        return out.toString();
    }

    private static String escapeCell(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace('\n', ' ');
    }
}
