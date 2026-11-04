package com.pilot.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.workspace.PathValidator;

/** 记忆候选区工具：只写 draft（不注入提示词）、追加而非覆盖、快照过回退语义。 */
class RememberToolTest {
    @TempDir
    Path workspace;

    private RememberTool tool() {
        return new RememberTool(new PathValidator(workspace));
    }

    @Test
    void appendsDatedEntryAndCreatesHeaderOnFirstWrite() throws Exception {
        RememberTool tool = tool();

        String first = tool.execute(Map.of("note", "mvnw 直链走官方 Central，改 core 后必须先 install 再编 server"));
        assertTrue(first.contains("首次创建"), first);

        Path draft = workspace.resolve(".pilot").resolve("memory-draft.md");
        String content = Files.readString(draft);
        assertTrue(content.contains("记忆候选区"), "首次写入要带说明头");
        assertTrue(content.contains("[") && content.contains("] mvnw 直链"), content);

        String second = tool.execute(Map.of("note", "上下文面板在输入框上方的 chip 里"));
        assertFalse(second.contains("首次创建"));
        assertEquals(2, Files.readAllLines(draft).stream().filter(line -> line.startsWith("- [")).count(), "追加而不是覆盖");
    }

    @Test
    void rejectsBlankAndOversizedNotes() {
        assertEquals("ERROR: 缺少参数 note", tool().execute(Map.of()));
        assertTrue(tool().execute(Map.of("note", "   ")).startsWith("ERROR"));
        String oversized = "长".repeat(501);
        assertTrue(tool().execute(Map.of("note", oversized)).contains("超过 500 字符上限"), "一行上限挡住整段粘贴");
    }

    @Test
    void writesGoThroughSnapshotsForRollbackSemantics() {
        boolean[] recorded = new boolean[1];
        SnapshotSink tracking = new SnapshotSink() {
            @Override
            public void beforeWrite(Path file) {
                recorded[0] = true;
            }

            @Override
            public void afterWrite(Path file) {
            }
        };
        new RememberTool(new PathValidator(workspace), tracking)
            .execute(Map.of("note", "测试后跑 npm test（pilot-web 目录内）"));
        assertTrue(recorded[0], "候选区写入要过快照，消息回退语义与普通文件一致");
    }

    @Test
    void multiLineNotesAreFlattenedToOneEntry() throws Exception {
        tool().execute(Map.of("note", "第一行\n第二行"));
        Path draft = workspace.resolve(".pilot").resolve("memory-draft.md");
        assertEquals(1, Files.readAllLines(draft).stream().filter(line -> line.startsWith("- [")).count(),
            "换行压平成一行，条目数不虚增");
    }

    @Test
    void marksItselfAsAWriteForPermissionGating() {
        assertFalse(tool().readOnly(), "写真实文件，按写操作走权限档位审批");
        assertEquals("remember", tool().name());
    }
}
