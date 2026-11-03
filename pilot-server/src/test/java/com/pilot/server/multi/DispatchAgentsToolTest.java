package com.pilot.server.multi;

import com.pilot.core.multi.AgentProfile;
import com.pilot.core.multi.AgentProfileCatalog;
import com.pilot.core.multi.AgentScope;
import com.pilot.core.multi.TaskSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchAgentsToolTest {

    @Test
    void parsesCommandWhitelistIntoScope() {
        List<TaskSpec> specs = DispatchAgentsTool.parseTasks(Map.of("tasks", List.of(Map.of(
                "id", "T1",
                "goal", "跑测试并核对结果",
                "files", List.of("pilot-core/**"),
                "tools", List.of("read_*", "run_command"),
                "commands", List.of("mvn test"),
                "deny_commands", List.of("mvn deploy"),
                "writable", false))));

        AgentScope scope = specs.get(0).scope();
        assertTrue(scope.allowsTool("read_file"));
        assertTrue(scope.allowsCommand("mvn test -q"));
        assertFalse(scope.allowsCommand("mvn deploy"));
        assertFalse(scope.writable());
    }

    /** 单写者原则：互不依赖又写同一批文件的任务会被并行调度，必然互相覆盖。 */
    @Test
    void rejectsParallelWritersOnTheSameFiles() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                DispatchAgentsTool.parseTasks(Map.of("tasks", List.of(
                        Map.of("id", "T1", "goal", "改后端", "files", List.of("src/**"), "writable", true),
                        Map.of("id", "T2", "goal", "改同一批文件", "files", List.of("src/**"),
                                "writable", true)))));

        assertTrue(error.getMessage().contains("并行写同一批文件"), error.getMessage());
    }

    @Test
    void allowsOverlappingFilesWhenTasksAreSerialized() {
        List<TaskSpec> specs = DispatchAgentsTool.parseTasks(Map.of("tasks", List.of(
                Map.of("id", "T1", "goal", "改后端", "files", List.of("src/**"), "writable", true),
                Map.of("id", "T2", "goal", "接着改", "files", List.of("src/**"), "writable", true,
                        "depends_on", List.of("T1")))));

        assertEquals(2, specs.size(), "有依赖关系的任务是串行执行，不构成并行写入");
    }

    @Test
    void characterClassGlobCannotBypassSingleWriterCheck() {
        assertThrows(IllegalArgumentException.class, () -> DispatchAgentsTool.parseTasks(Map.of("tasks", List.of(
                Map.of("id", "T1", "goal", "改文件", "files", List.of("src/[AB].java"), "writable", true),
                Map.of("id", "T2", "goal", "改同一个文件", "files", List.of("src/A.java"), "writable", true)))));
    }

    @Test
    void allowsDisjointFileRanges() {
        List<TaskSpec> specs = DispatchAgentsTool.parseTasks(Map.of("tasks", List.of(
                Map.of("id", "T1", "goal", "改后端", "files", List.of("server/**"), "writable", true),
                Map.of("id", "T2", "goal", "改前端", "files", List.of("web/**"), "writable", true))));

        assertEquals(2, specs.size());
    }

    @Test
    void defaultsToReadOnlyWhenWritableIsMissing() {
        List<TaskSpec> specs = DispatchAgentsTool.parseTasks(Map.of("tasks", List.of(Map.of(
                "id", "T1", "goal", "只做代码调研"))));

        AgentScope scope = specs.get(0).scope();
        assertFalse(scope.writable());
        assertTrue(scope.allowsPath("README.md"));
        assertFalse(scope.allowsCommand(""));
    }

    /** agent 引用：profile 提供缺省白名单与角色前缀；任务显式字段优先，不放宽。 */
    @Test
    void agentReferenceAppliesProfileDefaultsAndRolePrefix(@TempDir Path agentsDir) throws IOException {
        Files.writeString(agentsDir.resolve("dba.md"), """
                ---
                description: 数据库审查
                tools: [read_*, search_*]
                files: [src/main/resources/db/**]
                commands: [git diff]
                deny-commands: [git push]
                ---
                你是数据库脚本审查员。
                """);
        AgentProfileCatalog catalog = AgentProfileCatalog.scan(List.of(agentsDir));
        Map<String, Object> task = Map.of(
                "id", "T1",
                "goal", "检查迁移脚本",
                "agent", "dba");

        // 目录里没有 dba：引用必须明确报错，不能静默退回普通任务
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> DispatchAgentsTool.parseTasks(Map.of("tasks", List.of(task)), AgentProfileCatalog.EMPTY));
        assertTrue(missing.getMessage().contains("dba"), missing.getMessage());

        // 目录里有 dba：白名单取 profile，goal 带角色前缀
        List<TaskSpec> specs = DispatchAgentsTool.parseTasks(Map.of("tasks", List.of(task)), catalog);
        TaskSpec spec = specs.get(0);
        assertTrue(spec.goal().startsWith("【角色设定】\n你是数据库脚本审查员。"));
        assertTrue(spec.goal().endsWith("【任务目标】\n检查迁移脚本"));
        AgentScope scope = spec.scope();
        assertTrue(scope.allowsPath("src/main/resources/db/V1.sql"));
        assertFalse(scope.allowsPath("src/Main.java"));
        assertTrue(scope.allowsCommand("git diff"));
        assertFalse(scope.allowsCommand("git push"));
        assertFalse(scope.writable());

        // 任务显式收窄优先：文件范围以任务为准
        Map<String, Object> narrowed = Map.of(
                "id", "T2", "goal", "g", "agent", "dba",
                "files", List.of("docs/**"));
        TaskSpec spec2 = DispatchAgentsTool.parseTasks(
                Map.of("tasks", List.of(narrowed)), catalog).get(0);
        assertFalse(spec2.scope().allowsPath("src/main/resources/db/V1.sql"));
    }
}

