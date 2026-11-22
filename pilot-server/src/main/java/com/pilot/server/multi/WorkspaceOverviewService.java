package com.pilot.server.multi;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.pilot.core.coordination.CoordinationScopeResolver;
import com.pilot.core.coordination.ProjectCoordinationScope;
import com.pilot.core.event.ArbitrationRequired;
import com.pilot.core.event.ArbitrationResolved;
import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.TaskUpdated;
import com.pilot.core.multi.ChangeStatus;
import com.pilot.core.project.ProjectRepository;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionRepository;
import com.pilot.core.tool.command.CommandTaskManager;
import com.pilot.core.workspace.PathValidator;
import com.pilot.server.run.RunRegistry;

import lombok.extern.slf4j.Slf4j;

/**
 * 工作区聚合查询：一次拿到「这个工作区里所有会话在干什么」。
 *
 * <p>只读：不返回 diff 正文、不写文件，也不替各会话决定谁该退让。
 * 会话是否属于同一工作区不再逐个比较路径，而由 {@link ProjectCoordinationScope} 统一判定：
 * 项目绑定的全部文件夹都参与会话发现与未接受改动的聚合。
 */
@Slf4j
@Service
public class WorkspaceOverviewService {
    /** 每个会话只回看最近这些事件：足够还原任务与仲裁现状，又不把整段历史读进内存。 */
    private static final int RECENT_EVENTS = 200;

    private final SessionRepository sessions;
    private final SandboxStore sandboxes;
    private final RunRegistry runs;
    private final CommandTaskManager commands;
    private final CoordinationScopeResolver scopes;

    public WorkspaceOverviewService(SessionRepository sessions, SandboxStore sandboxes, RunRegistry runs,
        CommandTaskManager commands, ProjectRepository projects) {
        this.sessions = sessions;
        this.sandboxes = sandboxes;
        this.runs = runs;
        this.commands = commands;
        this.scopes = new CoordinationScopeResolver(projects);
    }

    /**
     * 汇总某个工作区里所有会话的当前状态。
     *
     * <p>入口目录可能是项目里的任何一个文件夹：取会话或项目解析出的完整协调范围，
     * 因此「主目录不同、但共享同一附加目录」的会话也会一起返回。
     *
     * @throws IllegalArgumentException 工作区不存在或不是目录
     */
    public Overview overview(String workspace) {
        Path root = new PathValidator(Path.of(workspace)).root();
        String key = root.toString();
        List<Session> all = sessions.list().stream().sorted(Comparator.comparing(Session::id)).toList();
        ProjectCoordinationScope scope = scopes.scopeForWorkspace(key, all);
        Map<String, List<String>> pending = pendingFiles(scope);
        Map<String, List<CommandView>> running = runningCommands(scope);
        List<SessionView> views = new ArrayList<>();
        for (Session session : all) {
            if (scopes.resolve(session).sharesCoordinationWith(scope)) {
                views.add(view(session, pending.getOrDefault(session.id(), List.of()),
                    running.getOrDefault(session.id(), List.of())));
            }
        }
        return new Overview(key, views, fileOwners(pending), Instant.now(),
            scope.directories().stream().map(Path::toString).toList(), scope.projectId());
    }

    private SessionView view(Session session, List<String> pendingFiles, List<CommandView> runningCommands) {
        Map<String, TaskView> tasks = new LinkedHashMap<>();
        Map<String, ArbitrationView> arbitrations = new LinkedHashMap<>();
        for (EventEnvelope envelope : sessions.eventsPage(session.id(), null, RECENT_EVENTS)) {
            if (envelope.payload() instanceof TaskUpdated task) {
                tasks.put(task.taskId(), new TaskView(task.taskId(), task.title(), task.status(), task.detail()));
            } else if (envelope.payload() instanceof ArbitrationRequired required) {
                arbitrations.put(required.arbitrationId(),
                    new ArbitrationView(required.arbitrationId(), required.kind(), required.taskId()));
            } else if (envelope.payload() instanceof ArbitrationResolved resolved) {
                arbitrations.remove(resolved.arbitrationId());
            }
        }
        return new SessionView(session.id(), session.title(), runs.activeRun(session.id()).isPresent(),
            List.copyOf(tasks.values()), List.copyOf(arbitrations.values()), pendingFiles, runningCommands);
    }

    /** 协调范围内各会话尚未接受的改动文件，按会话分组。 */
    private Map<String, List<String>> pendingFiles(ProjectCoordinationScope scope) {
        Map<String, List<String>> bySession = new LinkedHashMap<>();
        for (SandboxStore.Holder holder : sandboxes.holders(scope)) {
            holder.changes().stream().filter(change -> change.status() == ChangeStatus.PENDING)
                .map(change -> change.path())
                .forEach(path -> bySession.computeIfAbsent(holder.sessionId(), ignored -> new ArrayList<>()).add(path));
        }
        return bySession;
    }

    /** 范围里每个目录上跑着的命令；同一任务只记一次。 */
    private Map<String, List<CommandView>> runningCommands(ProjectCoordinationScope scope) {
        Map<String, List<CommandView>> bySession = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Path directory : scope.directories()) {
            for (CommandTaskManager.Task task : commands.runningInWorkspace(directory)) {
                if (seen.add(task.taskId())) {
                    bySession.computeIfAbsent(task.sessionId(), ignored -> new ArrayList<>())
                        .add(new CommandView(task.taskId(), task.command()));
                }
            }
        }
        return bySession;
    }

    /** 只有被两个以上会话同时改着的文件才算「重叠」，单个会话的改动不上榜。 */
    private List<FileOwner> fileOwners(Map<String, List<String>> pending) {
        Map<String, Set<String>> owners = new LinkedHashMap<>();
        pending.forEach((sessionId, paths) -> paths
            .forEach(path -> owners.computeIfAbsent(path, ignored -> new LinkedHashSet<>()).add(sessionId)));
        return owners.entrySet().stream().filter(entry -> entry.getValue().size() > 1)
            .map(entry -> new FileOwner(entry.getKey(), List.copyOf(entry.getValue()))).toList();
    }

    /**
     * 某个工作区的当前全貌；{@code fileOwners} 只列被多个会话同时改着的文件。
     *
     * @param coordinationDirectories 本次聚合用的规范化目录；窗口内所有会话都在这个范围里
     * @param projectId               范围所属项目；没有项目时为 {@code null}
     */
    public record Overview(String workspace, List<SessionView> sessions, List<FileOwner> fileOwners,
        Instant generatedAt, List<String> coordinationDirectories, String projectId) {
    }

    public record SessionView(String sessionId, String title, boolean running, List<TaskView> tasks,
        List<ArbitrationView> arbitrations, List<String> pendingFiles, List<CommandView> runningCommands) {
    }

    public record TaskView(String taskId, String title, String status, String detail) {
    }

    /** 尚未解决的仲裁；{@code kind} 为 {@code cross-session} 表示别的会话挡着同一批文件。 */
    public record ArbitrationView(String arbitrationId, String kind, String taskId) {
    }

    public record CommandView(String taskId, String command) {
    }

    public record FileOwner(String path, List<String> sessions) {
    }
}
