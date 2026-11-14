package com.pilot.core.hook;

import java.util.Map;

import com.pilot.core.exec.ExecutionBackends;
import com.pilot.core.exec.ExecutionIsolation;
import com.pilot.core.exec.ExecutionLimits;
import com.pilot.core.exec.ExecutionRequest;
import com.pilot.core.exec.ExecutionResult;
import com.pilot.core.exec.ExecutionSettings;
import com.pilot.core.exec.OsSandbox;
import com.pilot.core.message.ToolNames;
import com.pilot.core.tool.Tool;

/** 只由执行器内部构造；不注册为模型工具。权限规则使用同一个 run_command 名称。 */
public final class HookCommandTool implements Tool {
    private final Hooks.Command command;
    private final ExecutionBackends.Selection selection;
    private Hooks.CommandResult result;

    public HookCommandTool(Hooks.Command command, ExecutionBackends.Selection selection) {
        this.command = command;
        this.selection = selection;
    }

    @Override
    public String name() {
        return ToolNames.RUN_COMMAND;
    }

    @Override
    public String description() {
        return "执行配置钩子；遵守当前命令权限与隔离策略";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object");
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public boolean confirm(Map<String, Object> args) {
        return command.requiresTrust();
    }

    @Override
    public String approvalDescription(Map<String, Object> args) {
        return "配置钩子将执行 shell 命令。本次确认只适用于此命令：\n来源=" + command.source() + "\n工作区=" + command.workspace() + "\n触发="
            + args.get("event") + " / " + args.get("trigger_tool") + "\n命令=" + command.spec().command();
    }

    @Override
    public String execute(Map<String, Object> args) {
        ExecutionBackends.Selection selected =
            selection == null ? ExecutionBackends.select(ExecutionSettings.fromSystemProperties()) : selection;
        if (selected.degraded() || !selected.backend().available()
            || selected.requested() == ExecutionIsolation.CONTAINER && !selected.isolated()) {
            result = new Hooks.CommandResult("ERROR: 钩子所需隔离不可用，未执行：" + selected.reason(), false, false);
            return result.output();
        }
        ExecutionLimits base = selected.settings().limits();
        var limits = new ExecutionLimits(
            Math.min(command.spec().timeoutSeconds(), base.timeoutSeconds() > 0 ? base.timeoutSeconds() : 300),
            Math.min(32000, base.maxOutputBytes()), base.memoryMb(), base.cpus(), base.pidsLimit());
        ExecutionRequest request = ExecutionRequest.shell(command.spec().command(), command.workspace(),
            command.workspace(), Map.of(), selected.settings().network(), limits);
        String sandboxLabel = "";
        if (!selected.isolated()) {
            OsSandbox.Launch sandbox = OsSandbox.wrap(request.argv(), command.workspace());
            request = new ExecutionRequest(sandbox.command(), request.script(), request.workdir(),
                request.workspaceRoot(), request.env(), request.network(), request.limits());
            sandboxLabel = "\n" + sandbox.label();
        }
        ExecutionResult executed = selected.backend().run(request);
        String output = executed.ok() ? executed.output().strip() : "ERROR: 钩子退出码 " + executed.exitCode()
            + (executed.timedOut() ? "（超时）" : "") + "：" + executed.error() + "\n" + executed.output();
        result = new Hooks.CommandResult(output + sandboxLabel, executed.ok(), true);
        return result.output();
    }

    public Hooks.CommandResult result(String deniedOutput) {
        return result == null ? new Hooks.CommandResult(deniedOutput, false, false) : result;
    }
}
