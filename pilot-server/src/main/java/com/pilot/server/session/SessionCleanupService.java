package com.pilot.server.session;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.pilot.core.browser.BrowserBackend;
import com.pilot.core.session.ConversationRepository;
import com.pilot.core.session.PlanRepository;
import com.pilot.core.session.RunCheckpointRepository;
import com.pilot.core.session.RunRepository;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.core.tool.CommandTaskManager;
import com.pilot.server.multi.SandboxStore;
import com.pilot.server.store.ToolCallJournalFactory;
import com.pilot.server.validation.ValidationLedger;
import com.pilot.server.work.FileWorkbenchService;
import com.pilot.server.work.SkillDraftService;
import com.pilot.server.work.WorkProductService;

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
    private final CommandTaskManager commandTasks;
    private final ObjectProvider<BrowserBackend> browser;
    private final ValidationLedger validations;
    private final ToolCallJournalFactory journals;
    private final RunCheckpointRepository checkpoints;
    private final WorkProductService products;
    private final SkillDraftService skillDrafts;
    private final FileWorkbenchService fileWorkbench;
    private final AttachmentService attachments;

    public SessionCleanupService(SessionService sessions, ConversationRepository conversations, PlanRepository plans,
        RunRepository runs, SandboxStore sandboxes, RunStarter runStarter, TaskControlService taskControls,
        CommandTaskManager commandTasks, ObjectProvider<BrowserBackend> browser, ValidationLedger validations,
        ToolCallJournalFactory journals, RunCheckpointRepository checkpoints, WorkProductService products,
        SkillDraftService skillDrafts, FileWorkbenchService fileWorkbench, AttachmentService attachments) {
        this.fileWorkbench = fileWorkbench;
        this.products = products;
        this.skillDrafts = skillDrafts;
        this.validations = validations;
        this.journals = journals;
        this.checkpoints = checkpoints;
        this.sessions = sessions;
        this.conversations = conversations;
        this.plans = plans;
        this.runs = runs;
        this.sandboxes = sandboxes;
        this.runStarter = runStarter;
        this.taskControls = taskControls;
        this.commandTasks = commandTasks;
        this.browser = browser;
        this.attachments = attachments;
    }

    /**
     * @return 会话是否存在并已被删除
     */
    public boolean delete(String sessionId) {
        // 子会话不在主列表里，不能依赖某个浏览器的本地标签替服务器做清理。
        for (Session child : sessions.list()) {
            if (sessionId.equals(child.parentSessionId())) {
                delete(child.id());
            }
        }
        commandTasks.stopSession(sessionId);
        BrowserBackend backend = browser.getIfAvailable();
        if (backend != null) {
            backend.closeSession(sessionId);
        }
        runStarter.activeRun(sessionId).ifPresent(runStarter::cancel);
        boolean removed = sessions.delete(sessionId);
        products.deleteBySession(sessionId);
        skillDrafts.deleteBySession(sessionId);
        // 文件工作台的批注、快照索引与预览缓存都属于会话；工作区里的真实文件保留给用户。
        fileWorkbench.deleteBySession(sessionId);
        // 聊天附件随会话一起回收，不留孤儿目录
        attachments.deleteBySession(sessionId);
        conversations.delete(sessionId);
        plans.delete(sessionId);
        runs.deleteBySession(sessionId);
        // 沙箱与任务看板跟着会话一起清掉，避免删除后仍能在接口里读到
        sandboxes.reset(sessionId);
        // 队列、续跑计数、运行槽位这些都是内存状态，不清理会一直占着
        runStarter.forget(sessionId);
        taskControls.delete(sessionId);
        // 校验证据、幂等账本与检查点都属于会话数据，删除后不应还能被诊断接口读到
        validations.deleteBySession(sessionId);
        journals.deleteSession(sessionId);
        checkpoints.deleteBySession(sessionId);
        return removed;
    }
}
