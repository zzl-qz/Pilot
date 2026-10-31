package com.pilot.core.multi;

import java.util.List;

/**
 * 一个自定义子代理的角色定义（{@code .pilot/agents/<name>.md}）。
 *
 * <p>对标 Claude Code 的 {@code .claude/agents}：frontmatter 声明权限视图（{@link AgentScope}
 * 的底座——工具名单 / 文件白名单 / 命令前后置白名单 / 是否可写），正文是子代理的角色提示词。
 * {@code dispatch_agents} 的任务可通过 {@code agent: <name>} 引用，免去每次重复声明白名单；
 * 显式写在任务里的字段优先，profile 只补缺省——因此 profile 是「预设」不是「越权放大」。
 *
 * <p>与技能（Skill）刻意分层：技能是「怎么做某类事」的指引，代理是「以什么身份与边界做事」
 * 的角色；两者都由目录即协议加载，但解析器与语义互不依赖。
 */
public record AgentProfile(String name, String description, List<String> tools,
                           List<String> files, List<String> denyFiles,
                           List<String> commands, List<String> denyCommands,
                           boolean writable, String prompt) {

    public AgentProfile {
        name = name == null ? "" : name.strip();
        description = description == null ? "" : description.strip();
        tools = tools == null ? List.of() : List.copyOf(tools);
        files = files == null ? List.of() : List.copyOf(files);
        denyFiles = denyFiles == null ? List.of() : List.copyOf(denyFiles);
        commands = commands == null ? List.of() : List.copyOf(commands);
        denyCommands = denyCommands == null ? List.of() : List.copyOf(denyCommands);
        prompt = prompt == null ? "" : prompt.strip();
    }

    /** 该 profile 的权限视图底座：任务里的显式字段应在 server 层覆盖后重建。 */
    public AgentScope scope() {
        return (writable ? AgentScope.readWrite(files, denyFiles) : AgentScope.readOnly(files, denyFiles))
                .withTools(tools, List.of())
                .withCommands(commands, denyCommands);
    }

    /**
     * 把 profile 应用到任务给定的字段上：任务显式给的优先，缺省的用 profile 补。
     *
     * <p>安全语义（单测锁定）：{@code writable} 完全由任务决定——JSON 参数没有「未声明」三态，
     * {@code false} 即显式只读，profile 的可写声明不能放大任务；否则自定义代理就成了越权通道。
     * 工具/文件/命令名单只在任务未声明时用 profile 补缺省，不取并集。
     *
     * @param taskFiles     任务声明的文件白名单；空表示未声明
     * @param taskDenyFiles 任务声明的文件黑名单；空表示未声明
     * @param taskTools     任务声明的工具白名单；空表示未声明
     * @param taskCommands  任务声明的命令白名单；空表示未声明
     */
    public AgentScope mergeScope(boolean taskWritable, List<String> taskFiles, List<String> taskDenyFiles,
                                 List<String> taskTools, List<String> taskCommands,
                                 List<String> taskDenyCommands) {
        boolean writable = taskWritable;
        List<String> files = taskFiles.isEmpty() ? this.files : taskFiles;
        List<String> denyFiles = taskDenyFiles.isEmpty() ? this.denyFiles : taskDenyFiles;
        List<String> tools = taskTools.isEmpty() ? this.tools : taskTools;
        List<String> commands = taskCommands.isEmpty() ? this.commands : taskCommands;
        List<String> denyCommands = taskDenyCommands.isEmpty() ? this.denyCommands : taskDenyCommands;
        return (writable ? AgentScope.readWrite(files, denyFiles) : AgentScope.readOnly(files, denyFiles))
                .withTools(tools, List.of())
                .withCommands(commands, denyCommands);
    }

    /** 子任务目标前拼角色设定段：子代理拿到的是「身份 + 目标」，不是裸任务。 */
    public String prefixGoal(String goal) {
        if (prompt.isEmpty()) {
            return goal;
        }
        return "【角色设定】\n" + prompt + "\n\n【任务目标】\n" + goal;
    }
}
