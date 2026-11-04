package com.pilot.server.multi;

import java.nio.file.Files;
import java.nio.file.Path;

import com.pilot.core.multi.DiffSandbox;
import com.pilot.core.multi.RiskLevel;
import com.pilot.server.config.PilotDataDir;

import tools.jackson.databind.json.JsonMapper;

/** 由测试启动独立 JVM，直接 halt 来验证不存在 finally 清理的真实进程退出。 */
public class WorkspaceApplyCrashMain {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path data = Path.of(args[1]);
        var mapper = JsonMapper.builder().build();
        Files.writeString(root.resolve("a"), "before");
        var journal = new WorkspaceApplyJournal(data.resolve("apply-journal"), mapper, (point, i) -> {
            if (point.equals(args[2]) && (i < 0 || i == 0)) {
                Runtime.getRuntime().halt(17);
            }
        });
        var store = new SandboxStore(mapper, new PilotDataDir(data.toString()), journal);
        var box = new DiffSandbox("sbx", "agent", "task", "");
        box.add("a", "before", "after", true, RiskLevel.LOW);
        box.add("new", null, "created", false, RiskLevel.LOW);
        store.add("s", box);
        store.accept("s", "sbx", root);
        throw new AssertionError("未到达故障注入点");
    }
}
