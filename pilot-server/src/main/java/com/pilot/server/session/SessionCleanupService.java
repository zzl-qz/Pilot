package com.pilot.server.session;

import com.pilot.core.session.ConversationRepository;
import com.pilot.core.session.PlanRepository;
import com.pilot.core.session.RunRepository;
import com.pilot.core.session.SessionService;
import com.pilot.server.multi.SandboxStore;
import org.springframework.stereotype.Component;

/**
 * 会话的级联删除：删一个会话要同时清掉它的事件、对话记忆、计划、运行记录、沙箱和内存队列。
 *
 * <p>删除入口有两个（会话列表删单个会话、删除整个项目），把清理步骤收在一处，
 * 避免新增一种存储时只改了其中一个入口。
 */
@Component
public class SessionCleanupService {
    private final SessionService sessions;
    private final ConversationRepository conversations;
    private final PlanRepository plans;
    private final RunRepository runs;
    private final SandboxStore sandboxes;
    private final RunStarter runStarter;
    private final TaskControlService taskControls;
    private final com.pilot.core.tool.CommandTaskManager commandTasks;

    public SessionCleanupService(SessionService sessions, ConversationRepository conversations,
                                 PlanRepository plans, RunRepository runs, SandboxStore sandboxes,
                                 RunStarter runStarter, TaskControlService taskControls,
                                 com.pilot.core.tool.CommandTaskManager commandTasks) {
        this.sessions = sessions;
        this.conversations = conversations;
        this.plans = plans;
        this.runs = runs;
        this.sandboxes = sandboxes;
        this.runStarter = runStarter;
        this.taskControls = taskControls;
        this.commandTasks = commandTasks;
    }

    /**
     * @return 会话是否存在并已被删除
     */
    public boolean delete(String sessionId) {
        commandTasks.stopSession(sessionId);
        runStarter.activeRun(sessionId).ifPresent(runStarter::cancel);
        boolean removed = sessions.delete(sessionId);
        conversations.delete(sessionId);
        plans.delete(sessionId);
        runs.deleteBySession(sessionId);
        // 沙箱与任务看板跟着会话一起清掉，避免删除后仍能在接口里读到
        sandboxes.reset(sessionId);
        // 队列、续跑计数、运行槽位这些都是内存状态，不清理会一直占着
        runStarter.forget(sessionId);
        taskControls.delete(sessionId);
        return removed;
    }
}
