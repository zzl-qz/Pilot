package com.pilot.server.session;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.pilot.core.event.RunFailed;
import com.pilot.core.session.InterruptedRun;
import com.pilot.core.session.RunCheckpoint;
import com.pilot.core.session.RunRepository;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.core.session.SessionStatus;

/**
 * 启动时的状态收敛：进程重启后，上一次运行不可能继续，必须把「运行」和「会话」一起收敛。
 *
 * <p>只改 runs 表是不够的——会话状态由事件驱动，缺了 {@code run.failed} 事件，
 * 会话会永远停在 RUNNING，客户端回放历史后一直显示「正在处理」。
 * 这里对每个被中断的运行补一条 {@code RunFailed("INTERRUPTED")}，事件驱动状态迁移到
 * {@code SessionStatus.INTERRUPTED}，界面即可显示「已中断 · 可继续」。
 *
 * <p>第二轮收敛检查点：释放上次遗留的租约、把状态标成 INTERRUPTED，并记录「停在第几步、
 * 下一步是什么」。这一步只做安全接管准备，不自动重放工具调用——真正的继续仍由用户触发。
 */
@Component
@org.springframework.context.annotation.DependsOn("historyEditCoordinator")
public class InterruptedRunRecovery implements ApplicationRunner {
    private com.pilot.server.maintenance.DataMaintenance maintenance =
        new com.pilot.server.maintenance.DataMaintenance();

    @org.springframework.beans.factory.annotation.Autowired
    void useMaintenance(com.pilot.server.config.PilotDataDir dataDir) {
        this.maintenance = dataDir.maintenance();
    }

    private static final Logger log = LoggerFactory.getLogger(InterruptedRunRecovery.class);
    private static final String MESSAGE = "服务重启导致本次运行中断，对话记忆已保留，可点「继续执行」接着跑。";

    private final RunRepository runs;
    private final SessionService sessions;
    private final RunStateTracker tracker;
    private final RecoveryReportService report;

    /** 兼容测试与无检查点场景。 */
    public InterruptedRunRecovery(RunRepository runs, SessionService sessions) {
        this(runs, sessions, null, null);
    }

    @Autowired
    public InterruptedRunRecovery(RunRepository runs, SessionService sessions, RunStateTracker tracker,
        RecoveryReportService report) {
        this.runs = runs;
        this.sessions = sessions;
        this.tracker = tracker;
        this.report = report;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (var activity = maintenance.activity()) {
            recover();
        }
    }

    private void recover() {
        Instant now = Instant.now();
        List<InterruptedRun> interrupted = runs.markInterrupted(now);
        Set<String> handled = new HashSet<>();
        for (InterruptedRun run : interrupted) {
            // 空白会话（从未发过消息）没有被中断的运行可言：给它补 run.failed 只会在界面上
            // 凭空造出一张「运行已中断 · 继续执行」的卡片，所以直接跳过，状态留原样。
            if (skippable(run.sessionId())) {
                continue;
            }
            handled.add(run.sessionId());
            closeSession(run.sessionId(), run.runId());
        }
        // runs 表可能压根没有对应记录（例如 run 行没写成或事件丢失），仍要收敛会话状态
        for (Session session : sessions.list()) {
            if (session.status().terminal() || handled.contains(session.id()) || !inFlight(session)) {
                continue;
            }
            // 有检查点时用检查点里的 runId，界面才能把「继续」关联到同一次运行
            String runId =
                tracker == null ? null : tracker.current(session.id()).map(RunCheckpoint::runId).orElse(null);
            closeSession(session.id(), runId == null ? "run_recovered-" + UUID.randomUUID() : runId);
        }
        if (tracker == null || report == null) {
            return;
        }
        try {
            List<RunStateTracker.RecoveryEntry> entries = tracker.recoverAfterRestart();
            String note = entries.isEmpty() ? "没有需要接管的遗留运行" : "已收敛 " + entries.size() + " 个遗留运行";
            report.record(tracker.instanceId(), entries, note);
            log.info("启动恢复完成：{}", note);
        } catch (RuntimeException e) {
            log.warn("启动恢复检查点失败", e);
            report.record(tracker.instanceId(), List.of(), "检查点恢复失败：" + e.getMessage());
        }
    }

    /** 会话有没有「正在进行中」的一轮：只有这种状态才可能被重启打断。 */
    private static boolean inFlight(Session session) {
        if (session.isBlank()) {
            return false;
        }
        return session.status() == SessionStatus.RUNNING || session.status() == SessionStatus.WAITING_APPROVAL;
    }

    /** 空白会话（或状态本来就不在进行中）跳过收敛：避免给从没跑过的对话补中断事件。 */
    private boolean skippable(String sessionId) {
        return sessions.find(sessionId).map(session -> !inFlight(session)).orElse(true);
    }

    private void closeSession(String sessionId, String runId) {
        try {
            sessions.publish(sessionId, runId, new RunFailed("INTERRUPTED", MESSAGE, true));
            log.warn("会话 {} 上次运行 {} 未正常结束，已补 run.failed(INTERRUPTED) 收敛为中断", sessionId, runId);
        } catch (RuntimeException e) {
            log.warn("收敛会话 {} 的中断状态失败：{}", sessionId, e.toString());
        }
    }
}
