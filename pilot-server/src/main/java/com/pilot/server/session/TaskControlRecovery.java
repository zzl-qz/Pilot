package com.pilot.server.session;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;

/** 重启后只有目标记录仍处于执行中才标记中断；用户暂停的任务继续保持暂停。 */
@Component
public class TaskControlRecovery implements ApplicationRunner {
    private com.pilot.server.maintenance.DataMaintenance maintenance =
        new com.pilot.server.maintenance.DataMaintenance();

    @org.springframework.beans.factory.annotation.Autowired
    void useMaintenance(com.pilot.server.config.PilotDataDir dataDir) {
        this.maintenance = dataDir.maintenance();
    }

    private final SessionService sessions;
    private final TaskControlService tasks;

    public TaskControlRecovery(SessionService sessions, TaskControlService tasks) {
        this.sessions = sessions;
        this.tasks = tasks;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (var activity = maintenance.activity()) {
            recover();
        }
    }

    private void recover() {
        for (Session session : sessions.list()) {
            TaskControlService.TaskContract task = tasks.load(session.id());
            if (task != null && "ACTIVE".equals(task.status())) {
                tasks.status(session.id(), "INTERRUPTED");
            }
        }
    }
}
