package com.pilot.server.session;

import com.pilot.core.session.SessionService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 重启后只有目标记录仍处于执行中才标记中断；用户暂停的任务继续保持暂停。 */
@Component
public class TaskControlRecovery implements ApplicationRunner {
    private final SessionService sessions;
    private final TaskControlService tasks;
    public TaskControlRecovery(SessionService sessions, TaskControlService tasks) {
        this.sessions = sessions;
        this.tasks = tasks;
    }
    @Override
    public void run(ApplicationArguments args) {
        for (var session : sessions.list()) {
            var task = tasks.load(session.id());
            if (task != null && "ACTIVE".equals(task.status())) tasks.status(session.id(), "INTERRUPTED");
        }
    }
}
