package com.pilot.server.session;

import com.pilot.core.event.RunStarted;
import com.pilot.core.event.RunFailed;
import com.pilot.core.session.SessionService;
import java.util.List;

/** HTTP 与定时器共用的已占位投递入口；调用前 claimRun，失败必释放。 */
public final class RunSubmissionService {
    private RunSubmissionService() { }
    public static void start(SessionService sessions, RunStarter runs, String sessionId, String runId,
                             String input, List<com.pilot.core.loop.Attachment> attachments,
                             List<Integer> imagePositions, TaskControlService.Requirements task, String skill) {
        submit(sessions, runs, sessionId, runId, new RunStarted(input, attachments, imagePositions),
                () -> runs.start(sessionId, runId, input, attachments, imagePositions, task, skill));
    }
    public static void resume(SessionService sessions, RunStarter runs, String sessionId, String runId) {
        submit(sessions, runs, sessionId, runId, new RunStarted("（继续上一次未完成的任务）"),
                () -> runs.resume(sessionId, runId));
    }
    private static void submit(SessionService sessions, RunStarter runs, String sessionId, String runId,
                               RunStarted started, Runnable action) {
        try { sessions.publish(sessionId, runId, started); action.run(); }
        catch (RuntimeException e) {
            runs.abandon(runId);
            try { sessions.publish(sessionId, runId, new RunFailed("START_FAILED", "启动失败：" + e.getMessage(), true)); }
            catch (RuntimeException ignored) { }
            throw e;
        }
    }
}
