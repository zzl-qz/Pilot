package com.pilot.server.multi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.pilot.core.event.SessionEvent;
import com.pilot.core.multi.AgentProfile;
import com.pilot.core.multi.AgentProfileCatalog;
import com.pilot.core.multi.AgentScope;
import com.pilot.core.multi.RiskLevel;
import com.pilot.core.multi.TaskSpec;
import com.pilot.core.tool.Tool;
import com.pilot.server.session.SpawnAgentTool;

/**
 * {@code dispatch_agents}：父 Agent 把任务 DAG 交给调度器，由内核拉子 Agent 执行。
 *
 * <p>父 Agent 不能自己写代码，只能通过这个工具派活；工具返回结构化报告，
 * 冲突 / 分歧 / 校验失败都会以「停下来等人工」的口径回传。
 */
public final class DispatchAgentsTool implements Tool {
    private static final int MAX_TASKS = 12;

    private final com.pilot.core.model.ModelClient executionClient;
    private com.pilot.core.permission.PermissionMode parentPermission;

    public DispatchAgentsTool withParentPermission(com.pilot.core.permission.PermissionMode permission) {
        this.parentPermission = permission;
        return this;
    }

    private final MultiAgentService service;
    private final String sessionId;
    private final String runId;
    private final String workspace;
    private final Consumer<SessionEvent> events;
    private final BooleanSupplier cancelled;
    /** 本 run 可用的自定义代理目录（两级扫描结果）；没有定义时为 EMPTY。 */
    private final AgentProfileCatalog profiles;
    /** 子 Agent 用量归集：计入父任务事故熔断与 run.usage 聚合。 */
    private final SpawnAgentTool.UsageSink usageSink;

    public DispatchAgentsTool(MultiAgentService service, String sessionId, String runId, String workspace,
        Consumer<SessionEvent> events, BooleanSupplier cancelled) {
        this(service, sessionId, runId, workspace, events, cancelled, null, AgentProfileCatalog.EMPTY, null);
    }

    public DispatchAgentsTool(MultiAgentService service, String sessionId, String runId, String workspace,
        Consumer<SessionEvent> events, BooleanSupplier cancelled, com.pilot.core.model.ModelClient executionClient) {
        this(service, sessionId, runId, workspace, events, cancelled, executionClient, AgentProfileCatalog.EMPTY, null);
    }

    public DispatchAgentsTool(MultiAgentService service, String sessionId, String runId, String workspace,
        Consumer<SessionEvent> events, BooleanSupplier cancelled, com.pilot.core.model.ModelClient executionClient,
        AgentProfileCatalog profiles) {
        this(service, sessionId, runId, workspace, events, cancelled, executionClient, profiles, null);
    }

    public DispatchAgentsTool(MultiAgentService service, String sessionId, String runId, String workspace,
        Consumer<SessionEvent> events, BooleanSupplier cancelled, com.pilot.core.model.ModelClient executionClient,
        AgentProfileCatalog profiles, SpawnAgentTool.UsageSink usageSink) {
        this.executionClient = executionClient;
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.runId = Objects.requireNonNull(runId, "runId must not be null");
        this.workspace = workspace;
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.cancelled = Objects.requireNonNull(cancelled, "cancelled must not be null");
        this.profiles = profiles == null ? AgentProfileCatalog.EMPTY : profiles;
        this.usageSink = usageSink == null ? SpawnAgentTool.UsageSink.none() : usageSink;
    }

    @Override
    public String name() {
        return "dispatch_agents";
    }

    @Override
    public String description() {
        return promptDescription();
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String,
            Object> fields = Map.ofEntries(Map.entry("id", Map.of("type", "string", "description", "任务编号，会话内唯一，例如 T1")),
                Map.entry("title", Map.of("type", "string", "description", "任务标题，界面展示用")),
                Map.entry("goal", Map.of("type", "string", "description", "完整目标：做什么、做到什么算完成")),
                Map.entry("agent", Map.of("type", "string", "description", agentFieldDescription())),
                Map.entry("depends_on",
                    Map.of("type", "array", "items", Map.of("type", "string"), "description", "前置任务编号；为空表示可立即并行")),
                Map.entry("files",
                    Map.of("type", "array", "items", Map.of("type", "string"), "description",
                        "文件白名单 glob，例如 pilot-core/src/**/*.java")),
                Map.entry("deny_files",
                    Map.of("type", "array", "items", Map.of("type", "string"), "description", "禁止访问的 glob")),
                Map.entry("writable", Map.of("type", "boolean", "description", "是否允许提交沙箱改动；只读调研填 false")),
                Map.entry("tools",
                    Map.of("type", "array", "items", Map.of("type", "string"), "description",
                        "可用工具白名单，支持 * 通配符，例如 read_*/search_text/run_command")),
                Map.entry("commands",
                    Map.of("type", "array", "items", Map.of("type", "string"), "description",
                        "命令白名单，按参数前缀匹配，例如 mvn test / git status；留空沿用内置可执行白名单")),
                Map.entry(
                    "deny_commands",
                    Map.of("type", "array", "items", Map.of("type", "string"), "description",
                        "禁止执行的命令前缀，优先级高于 commands")),
                Map.entry(
                    "risk",
                    Map.of("type", "string", "enum", List.of("low", "medium", "high"), "description",
                        "风险等级，高风险必须人工审批")),
                Map.entry("reviewers",
                    Map.of("type", "integer", "description", "同一任务并行出方案的子 Agent 数量（1-3），结论不一致会触发仲裁")));
        Map<String, Object> task = Map.of("type", "object", "properties", fields, "required", List.of("id", "goal"),
            "additionalProperties", false);
        return Map.of("type", "object", "properties",
            Map.of("tasks", Map.of("type", "array", "items", task, "description", "任务列表，按依赖关系组成 DAG；建议 2-6 个"),
                "reason", Map.of("type", "string", "description", "一句话说明为什么这样拆分")),
            "required", List.of("tasks"), "additionalProperties", false);
    }

    /** agent 字段的动态说明：有可用代理时点名角色与职责，模型不必猜名字。 */
    private String agentFieldDescription() {
        StringBuilder text =
            new StringBuilder("引用自定义子代理（.pilot/agents/<name>.md）的名字：工具/文件/命令白名单与角色提示词从定义装配，" + "本任务里显式声明的字段优先、缺省用定义补");
        String names = profiles.nameListText();
        if (!names.isEmpty()) {
            text.append("。可用代理：").append(names);
        }
        return text.toString();
    }

    /** 会派发子 Agent、产生沙箱改动与命令执行，按写操作对待，走当前权限档位。 */
    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public String execute(Map<String, Object> args) {
        List<TaskSpec> specs;
        try {
            specs = parseTasks(args, profiles);
        } catch (IllegalArgumentException e) {
            return "ERROR: 任务计划不合法：" + e.getMessage();
        }
        try {
            MultiAgentService.Result result =
                service.dispatch(new MultiAgentService.Dispatch(sessionId, runId, workspace, specs, events, cancelled,
                    executionClient, usageSink).withParentPermission(parentPermission));
            return result.report();
        } catch (IllegalArgumentException e) {
            return "ERROR: 任务计划不合法：" + e.getMessage();
        } catch (RuntimeException e) {
            return "ERROR: 多智能体调度失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    static List<TaskSpec> parseTasks(Map<String, Object> args) {
        return parseTasks(args, AgentProfileCatalog.EMPTY);
    }

    /** @param profiles 本 run 可用的自定义代理目录；空目录时 agent 字段只能引用失败 */
    static List<TaskSpec> parseTasks(Map<String, Object> args, AgentProfileCatalog profiles) {
        Object raw = args == null ? null : args.get("tasks");
        if (!(raw instanceof Collection<?> collection) || collection.isEmpty()) {
            throw new IllegalArgumentException("tasks 不能为空");
        }
        if (collection.size() > MAX_TASKS) {
            throw new IllegalArgumentException("一次最多派发 " + MAX_TASKS + " 个任务，请先拆分批次");
        }
        List<TaskSpec> specs = new ArrayList<>();
        for (Object item : collection) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("每个任务必须是对象");
            }
            specs.add(toSpec(map, profiles));
        }
        // 单写者原则：互不依赖又写同一批文件的任务会被并行调度，必然互相覆盖，派发阶段就拒绝
        List<String> overlaps = com.pilot.core.multi.TaskGraph.overlappingWriters(specs);
        if (!overlaps.isEmpty()) {
            throw new IllegalArgumentException(
                "以下任务会并行写同一批文件：" + String.join("；", overlaps) + "。请给其中一个加 depends_on 串行执行，或把 files 范围拆开互不重叠。");
        }
        return List.copyOf(specs);
    }

    private static TaskSpec toSpec(Map<?, ?> map, AgentProfileCatalog profiles) {
        String id = text(map.get("id"));
        String goal = text(map.get("goal"));
        String title = text(map.get("title"));
        List<String> dependsOn = strings(map.get("depends_on"));
        List<String> files = globs(strings(map.get("files")), "files");
        List<String> denyFiles = globs(strings(map.get("deny_files")), "deny_files");
        List<String> tools = strings(map.get("tools"));
        List<String> commands = strings(map.get("commands"));
        List<String> denyCommands = strings(map.get("deny_commands"));
        RiskLevel risk = RiskLevel.of(text(map.get("risk")));
        boolean writable = bool(map.get("writable"));
        int reviewers = number(map.get("reviewers"));
        String agentName = text(map.get("agent"));
        AgentProfile profile = null;
        if (!agentName.isEmpty()) {
            profile = profiles.find(agentName).orElseThrow(() -> new IllegalArgumentException("引用的代理 " + agentName
                + " 不存在（可用：" + (profiles.nameListText().isEmpty() ? "无" : profiles.nameListText()) + "）"));
        }
        AgentScope scope = profile == null
            ? (writable ? AgentScope.readWrite(files, denyFiles) : AgentScope.readOnly(files, denyFiles))
                .withTools(tools, List.of()).withCommands(commands, denyCommands)
            : profile.mergeScope(writable, files, denyFiles, tools, commands, denyCommands);
        String effectiveGoal = profile == null ? goal : profile.prefixGoal(goal);
        return new TaskSpec(id, title, effectiveGoal, dependsOn, scope, risk, reviewers);
    }

    /** 白名单里出现绝对路径或 {@code ..} 直接拒绝：宁可派发失败，也不要给出可能越界的范围。 */
    private static List<String> globs(List<String> values, String field) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String normalized = value.replace('\\', '/').strip();
            if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
                throw new IllegalArgumentException(field + " 不接受绝对路径: " + value);
            }
            for (String part : normalized.split("/")) {
                if ("..".equals(part)) {
                    throw new IllegalArgumentException(field + " 不接受上级目录: " + value);
                }
            }
            result.add(normalized);
        }
        return result;
    }

    private static List<String> strings(Object value) {
        if (value instanceof Collection<?> collection) {
            List<String> result = new ArrayList<>();
            for (Object item : collection) {
                String text = text(item);
                if (!text.isEmpty()) {
                    result.add(text);
                }
            }
            return result;
        }
        String single = text(value);
        return single.isEmpty() ? List.of() : List.of(single);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        String text = text(value);
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }

    private static int number(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(text(value));
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
