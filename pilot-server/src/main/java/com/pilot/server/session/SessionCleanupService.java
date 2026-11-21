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
import com.pilot.core.tool.command.CommandTaskManager;
import com.pilot.server.attachment.AttachmentReferences;
import com.pilot.server.attachment.AttachmentService;
import com.pilot.server.common.data.DataMaintenance;
import com.pilot.server.common.data.PilotDataDir;
import com.pilot.server.common.data.StorageHealth;
import com.pilot.server.multi.SandboxStore;
import com.pilot.server.run.RunStarter;
import com.pilot.server.run.TaskControlService;
import com.pilot.server.session.notes.SessionNotes;
import com.pilot.server.store.ToolCallJournalFactory;
import com.pilot.server.validation.ValidationLedger;
import com.pilot.server.work.DocumentService;
import com.pilot.server.work.FileWorkbenchService;
import com.pilot.server.work.SkillDraftService;
import com.pilot.server.work.WorkProductService;

/**
 * 会话的级联删除：删一个会话要同时清掉它的事件、对话记忆、计划、运行记录、沙箱、文档工作台和内存队列。
 *
 * <p>删除入口有两个（会话列表删单个会话、删除整个项目），把清理步骤收在一处，
 * 避免新增一种存储时只改了其中一个入口。会话记录最后删；中途失败时会话保留并返回可重试错误，
 * 同一个 DELETE 请求可以继续完成剩余清理。
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
    private final DocumentService documents;
    private final AttachmentService attachments;
    private final AttachmentReferences attachmentReferences;
    private final SessionNotes notes;
    private final DataMaintenance maintenance;

    public SessionCleanupService(SessionService sessions, ConversationRepository conversations, PlanRepository plans,
        RunRepository runs, SandboxStore sandboxes, RunStarter runStarter, TaskControlService taskControls,
        CommandTaskManager commandTasks, ObjectProvider<BrowserBackend> browser, ValidationLedger validations,
        ToolCallJournalFactory journals, RunCheckpointRepository checkpoints, WorkProductService products,
        SkillDraftService skillDrafts, FileWorkbenchService fileWorkbench, DocumentService documents,
        AttachmentService attachments, PilotDataDir dataDir, AttachmentReferences attachmentReferences,
        SessionNotes notes) {
        this.fileWorkbench = fileWorkbench;
        this.documents = documents;
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
        this.attachmentReferences = attachmentReferences;
        this.notes = notes;
        this.maintenance = dataDir.maintenance();
    }

    /**
     * @return 会话是否存在并已被删除
     */
    public boolean delete(String sessionId) {
        String area = "session-cleanup:" + sessionId;
        try (var activity = maintenance.activity()) {
            synchronized (attachmentReferences) {
                boolean removed = deleteSession(sessionId);
                StorageHealth.recovered(area);
                return removed;
            }
        } catch (CleanupException e) {
            throw e;
        } catch (RuntimeException e) {
            StorageHealth.markDegraded(area, "会话 " + sessionId + " 清理未完成，可重试：" + e.getMessage());
            throw new CleanupException(sessionId, "会话清理未完成，可重试：" + e.getMessage(), e);
        }
    }

    private boolean deleteSession(String sessionId) {
        // 子会话不在主列表里，不能依赖某个浏览器的本地标签替服务器做清理。
        for (Session child : sessions.list()) {
            if (sessionId.equals(child.parentSessionId())) {
                deleteSession(child.id());
            }
        }
        commandTasks.stopSession(sessionId);
        BrowserBackend backend = browser.getIfAvailable();
        if (backend != null) {
            backend.closeSession(sessionId);
        }
        runStarter.activeRun(sessionId).ifPresent(runStarter::cancel);
        products.deleteBySession(sessionId);
        skillDrafts.deleteBySession(sessionId);
        notes.deleteBySession(sessionId);
        // 文件工作台的批注、快照索引与预览缓存都属于会话；工作区里的真实文件保留给用户。
        fileWorkbench.deleteBySession(sessionId);
        // 文档工作台的文档、版本、导出索引和 studio/<docId> 导出目录同样属于会话。
        documents.deleteBySession(sessionId);
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
        // 会话记录最后删：前面的清理失败时保留会话，调用方可以原样重试 DELETE，
        // 而不是得到一个「会话已 404、文档却还在」的不可恢复状态。
        boolean removed = sessions.delete(sessionId);
        try {
            if (!attachmentReferences.retainedOwners().contains(AttachmentService.storageDirectory(sessionId))) {
                attachments.deleteBySession(sessionId);
            }
        } catch (RuntimeException e) {
            StorageHealth.markDegraded("attachment-purge", "读取附件引用失败，已保留附件：" + e.getMessage());
        }
        return removed;
    }

    /** 删除未完成、但会话仍在，可用同一个 DELETE 请求重试。 */
    public static final class CleanupException extends RuntimeException {
        private final String sessionId;

        public CleanupException(String sessionId, String message, Throwable cause) {
            super(message, cause);
            this.sessionId = sessionId;
        }

        public String sessionId() {
            return sessionId;
        }

        public boolean retryable() {
            return true;
        }
    }
}
