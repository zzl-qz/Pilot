package com.pilot.server.agent;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.pilot.core.tool.Tool;
import com.pilot.server.multi.WorkspaceOverviewService;

/**
 * 监督会话用的只读汇总：当前工作区里谁在跑、卡在哪、哪些文件撞在一起。
 *
 * <p>只读，不返回文件正文，也不提供任何改代码或接受变更的通道；
 * 它给出的只是建议，最终仍由用户决定。
 */
public final class WorkspaceOverviewTool implements Tool {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final WorkspaceOverviewService overviews;
    private final Path workspace;

    public WorkspaceOverviewTool(WorkspaceOverviewService overviews, Path workspace) {
        this.overviews = overviews;
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "workspace_overview";
    }

    @Override
    public String description() {
        return "汇总当前工作区里所有会话在跑什么、卡在哪：谁占着运行、哪些文件还没被接受、谁在等人工仲裁、哪些后台命令还在跑。" + "只读，不返回文件正文，不改任何文件；只给建议，由用户决定怎么处理。";
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties", Map.of(), "additionalProperties", false);
    }

    @Override
    public String execute(Map<String, Object> args) {
        WorkspaceOverviewService.Overview overview;
        try {
            overview = overviews.overview(workspace.toString());
        } catch (IllegalArgumentException e) {
            return "ERROR: 工作区不可用：" + e.getMessage();
        }
        return render(overview);
    }

    private String render(WorkspaceOverviewService.Overview overview) {
        StringBuilder text = new StringBuilder();
        text.append("工作区：").append(overview.workspace()).append('\n');
        text.append("生成时刻：").append(TIME.format(overview.generatedAt().atZone(ZoneId.systemDefault()))).append('\n');
        text.append("\n## 会话\n");
        if (overview.sessions().isEmpty()) {
            text.append("（这个工作区里还没有会话）\n");
        }
        for (WorkspaceOverviewService.SessionView session : overview.sessions()) {
            text.append("\n### ").append(session.title()).append("（").append(session.sessionId()).append("）\n");
            text.append("- 运行中：").append(session.running() ? "是" : "否").append('\n');
            text.append("- 未接受的文件：").append(joinOrNone(session.pendingFiles())).append('\n');
            text.append("- 后台命令：")
                .append(session.runningCommands().isEmpty() ? "无" : session.runningCommands().stream()
                    .map(command -> command.taskId() + " → " + command.command()).collect(Collectors.joining("、")))
                .append('\n');
            text.append("- 未解决仲裁：")
                .append(session.arbitrations().isEmpty() ? "无"
                    : session
                        .arbitrations().stream().map(arbitration -> arbitration.kind() + "（"
                            + arbitration.arbitrationId() + "，任务 " + arbitration.taskId() + "）")
                        .collect(Collectors.joining("、")))
                .append('\n');
            if (!session.tasks().isEmpty()) {
                text.append("- 任务：")
                    .append(session.tasks().stream()
                        .map(task -> task.taskId() + " " + task.title() + " [" + task.status() + "]")
                        .collect(Collectors.joining("、")))
                    .append('\n');
            }
        }
        text.append("\n## 多个会话同时改的文件\n");
        if (overview.fileOwners().isEmpty()) {
            text.append("（无）\n");
        }
        for (WorkspaceOverviewService.FileOwner owner : overview.fileOwners()) {
            text.append("- ").append(owner.path()).append("：").append(String.join("、", owner.sessions())).append('\n');
        }
        return text.toString().stripTrailing();
    }

    private String joinOrNone(List<String> paths) {
        return paths.isEmpty() ? "无" : String.join("、", paths);
    }
}
