package com.pilot.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.multi.AgentScope;
import com.pilot.core.multi.DiffSandbox;
import com.pilot.core.multi.RiskLevel;
import com.pilot.core.multi.SandboxChange;
import com.pilot.core.workspace.PathValidator;
import com.pilot.core.workspace.WorkspaceViolationException;

class SandboxToolsTest {

    private record Proposal(String path, String oldText, String newText) {
    }

    private static Path workspace(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src/A.java"), "class A {\n    int x = 1;\n}\n");
        Files.writeString(dir.resolve("secret.txt"), "top secret\n");
        return dir;
    }

    @Test
    void editToolOnlyRecordsProposal(@TempDir Path dir) throws IOException {
        Path root = workspace(dir);
        ArrayList<Proposal> proposals = new ArrayList<Proposal>();
        SandboxSink sink = (path, oldText, newText) -> proposals.add(new Proposal(path, oldText, newText));
        SandboxEditFileTool tool = new SandboxEditFileTool(new PathValidator(root), sink);

        String result =
            tool.execute(Map.of("path", "src/A.java", "old_string", "int x = 1;", "new_string", "int x = 2;"));

        assertFalse(result.startsWith("ERROR"), result);
        assertEquals(1, proposals.size());
        assertEquals("src/A.java", proposals.get(0).path());
        assertTrue(proposals.get(0).newText().contains("int x = 2;"));
        assertTrue(Files.readString(root.resolve("src/A.java")).contains("int x = 1;"), "真实文件必须保持不变");
    }

    @Test
    void sandboxBatchProducesOneAtomicProposal(@TempDir Path dir) throws IOException {
        Path root = workspace(dir);
        ArrayList<Proposal> proposals = new ArrayList<>();
        SandboxEditFileTool tool = new SandboxEditFileTool(new PathValidator(root),
            (path, oldText, newText) -> proposals.add(new Proposal(path, oldText, newText)));

        String result = tool.execute(
            Map.of("path", "src/A.java", "edits", List.of(Map.of("old_string", "class A", "new_string", "class B"),
                Map.of("old_string", "int x = 1;", "new_string", "int x = 2;"))));

        assertTrue(result.contains("替换 2 处"), result);
        assertEquals(1, proposals.size());
        assertTrue(proposals.get(0).newText().contains("class B"));
        assertTrue(proposals.get(0).newText().contains("int x = 2;"));
        assertTrue(Files.readString(root.resolve("src/A.java")).contains("class A"));
    }

    @Test
    void consecutiveEditsUseThePreviousSandboxProposal(@TempDir Path dir) throws IOException {
        Path root = workspace(dir);
        DiffSandbox sandbox = new DiffSandbox("sbx", "agent", "task", "");
        ReadTracker reads = new ReadTracker();
        SandboxSink sink = new SandboxSink() {
            @Override
            public void record(String path, String oldText, String newText) {
                sandbox.add(path, oldText, newText, oldText != null, RiskLevel.LOW);
            }

            @Override
            public String currentText(String path, String baselineText) {
                return sandbox.currentText(path, baselineText);
            }
        };
        SandboxEditFileTool tool = new SandboxEditFileTool(new PathValidator(root), sink, reads);
        ReadFileTool reader = new ReadFileTool(new PathValidator(root), reads, new SandboxSink() {
            @Override
            public void record(String path, String oldText, String newText) {
                sandbox.add(path, oldText, newText, oldText != null, RiskLevel.LOW);
            }

            @Override
            public String proposedText(String path) {
                return sandbox.proposedText(path);
            }
        });
        reader.execute(Map.of("path", "src/A.java"));

        tool.execute(Map.of("path", "src/A.java", "old_string", "int x = 1;", "new_string", "int x = 2;"));
        String result =
            tool.execute(Map.of("path", "src/A.java", "old_string", "int x = 2;", "new_string", "int x = 3;"));

        assertFalse(result.startsWith("ERROR"), result);
        assertTrue(sandbox.currentText("src/A.java", "").contains("int x = 3;"));
        assertTrue(sandbox.currentText("src/A.java", "").contains("class A"));
        assertTrue(Files.readString(root.resolve("src/A.java")).contains("int x = 1;"));
        String viewed = reader.execute(Map.of("path", "src/A.java"));
        assertTrue(viewed.contains("int x = 3;"), viewed);
    }

    @Test
    void consecutiveEditsKeepTheRealFileBaseline(@TempDir Path dir) throws IOException {
        Path root = workspace(dir);
        String original = Files.readString(root.resolve("src/A.java"));
        DiffSandbox sandbox = new DiffSandbox("sbx", "agent", "task", "");
        ReadTracker reads = new ReadTracker();
        SandboxEditFileTool tool = new SandboxEditFileTool(new PathValidator(root), sandboxSink(sandbox), reads);
        observe(root, reads, "src/A.java");

        tool.execute(Map.of("path", "src/A.java", "old_string", "int x = 1;", "new_string", "int x = 2;"));
        String result =
            tool.execute(Map.of("path", "src/A.java", "old_string", "int x = 2;", "new_string", "int x = 3;"));

        assertFalse(result.startsWith("ERROR"), result);
        assertEquals(1, sandbox.fileCount(), "同一路径只能留一条改动");
        SandboxChange change = sandbox.changes().get(0);
        assertEquals(DiffSandbox.fingerprint(original), change.baseHash(), "接受时会与真实文件比对指纹，baseHash 必须一直是原文的");
        assertTrue(change.diff().contains("int x = 1;"), change.diff());
        assertTrue(change.diff().contains("int x = 3;"), change.diff());
        assertFalse(change.diff().contains("int x = 2;"), "diff 应相对真实原文，而不是相对上一版提案：" + change.diff());
    }

    @Test
    void editAfterCreateUsesTheSandboxProposal(@TempDir Path dir) throws IOException {
        Path root = workspace(dir);
        DiffSandbox sandbox = new DiffSandbox("sbx", "agent", "task", "");
        ReadTracker reads = new ReadTracker();
        SandboxSink sink = sandboxSink(sandbox);
        SandboxCreateFileTool create = new SandboxCreateFileTool(new PathValidator(root), sink, reads);
        SandboxEditFileTool edit = new SandboxEditFileTool(new PathValidator(root), sink, reads);

        String created = create.execute(Map.of("path", "src/New.java", "content", "class New {\n    int v = 1;\n}\n"));
        assertFalse(created.startsWith("ERROR"), created);
        String result =
            edit.execute(Map.of("path", "src/New.java", "old_string", "int v = 1;", "new_string", "int v = 2;"));

        assertFalse(result.startsWith("ERROR"), result);
        assertTrue(sandbox.currentText("src/New.java", "").contains("int v = 2;"));
        assertFalse(Files.exists(root.resolve("src/New.java")), "真实工作区不能被创建");
        SandboxChange change = sandbox.changes().get(0);
        assertFalse(change.existed(), "仍是新增文件，不能因为第二次编辑就变成修改");
        assertEquals(DiffSandbox.fingerprint(null), change.baseHash());
    }

    @Test
    void revertingAnEditRemovesTheChange(@TempDir Path dir) throws IOException {
        Path root = workspace(dir);
        DiffSandbox sandbox = new DiffSandbox("sbx", "agent", "task", "");
        ReadTracker reads = new ReadTracker();
        SandboxEditFileTool tool = new SandboxEditFileTool(new PathValidator(root), sandboxSink(sandbox), reads);
        observe(root, reads, "src/A.java");

        tool.execute(Map.of("path", "src/A.java", "old_string", "int x = 1;", "new_string", "int x = 2;"));
        String result =
            tool.execute(Map.of("path", "src/A.java", "old_string", "int x = 2;", "new_string", "int x = 1;"));

        assertFalse(result.startsWith("ERROR"), result);
        assertTrue(sandbox.isEmpty(), "改回原文后不应留下空改动");
    }

    @Test
    void windowsSeparatorsDoNotCreateDuplicates() {
        DiffSandbox sandbox = new DiffSandbox("sbx", "agent", "task", "");
        sandbox.add("src/A.java", "class A {}\n", "class A { int x; }\n", true, RiskLevel.LOW);
        sandbox.add("src\\A.java", "class A { int x; }\n", "class A { int y; }\n", true, RiskLevel.LOW);

        assertEquals(1, sandbox.fileCount(), "路径分隔符差异必须落到同一条改动上");
    }

    @Test
    void caseIdentityFollowsTheActualFilesystem(@TempDir Path dir) throws IOException {
        Path root = workspace(dir);
        Path upper = root.resolve("src/A.java");
        Path lower = root.resolve("src/a.java");
        boolean sameFile = Files.exists(lower) && Files.isSameFile(upper, lower);
        if (!sameFile) {
            Files.writeString(lower, "class Lower { int x = 4; }\n");
        }
        DiffSandbox sandbox = new DiffSandbox("sbx", "agent", "task", "");
        ReadTracker reads = new ReadTracker();
        observe(root, reads, "src/A.java");
        observe(root, reads, "src/a.java");
        SandboxEditFileTool tool = new SandboxEditFileTool(new PathValidator(root), sandboxSink(sandbox), reads);
        String first =
            tool.execute(Map.of("path", "src/A.java", "old_string", "int x = 1;", "new_string", "int x = 2;"));
        String second = tool.execute(Map.of("path", "src/a.java", "old_string", sameFile ? "int x = 2;" : "int x = 4;",
            "new_string", "int x = 3;"));
        assertFalse(first.startsWith("ERROR"), first);
        assertFalse(second.startsWith("ERROR"), second);
        assertEquals(sameFile ? 1 : 2, sandbox.fileCount());
        assertEquals(DiffSandbox.fingerprint(Files.readString(upper)), sandbox.changes().get(0).baseHash());
    }

    /** 走真正的读取工具观察文件，路径形态与编辑工具保持一致，避免绕开「先读后改」。 */
    private static void observe(Path root, ReadTracker reads, String path) {
        new ReadFileTool(new PathValidator(root), reads, SandboxSink.rejectAll()).execute(Map.of("path", path));
    }

    @Test
    void overlaidUpstreamIsReadableButNeverWritable() {
        DiffSandbox upstream = new DiffSandbox("sbx_up", "agent_up", "T1", "");
        upstream.add("src/Api.java", null, "interface Api {}\n", false, RiskLevel.LOW);
        SandboxSink upstreamSink = new SandboxSink() {
            @Override
            public void record(String path, String oldText, String newText) {
                throw new IllegalStateException("上游产物只读: " + path);
            }

            @Override
            public boolean proposed(String path) {
                return upstream.hasChange(path);
            }

            @Override
            public String proposedText(String path) {
                return upstream.proposedText(path);
            }
        };
        DiffSandbox own = new DiffSandbox("sbx_own", "agent_own", "T2", "");
        SandboxSink combined = sandboxSink(own).overlay(upstreamSink);

        assertEquals("interface Api {}\n", combined.proposedText("src/Api.java"), "下游必须能看到上游新建的文件");
        assertTrue(combined.proposed("src/Api.java"));
        assertEquals("磁盘内容", combined.currentText("src/Other.java", "磁盘内容"));

        combined.record("src/Own.java", null, "class Own {}\n");

        assertTrue(own.hasChange("src/Own.java"), "写入仍然只进下游自己的沙箱");
        assertEquals(1, own.fileCount());
    }

    private static SandboxSink sandboxSink(DiffSandbox sandbox) {
        return new SandboxSink() {
            @Override
            public void record(String path, String oldText, String newText) {
                sandbox.add(path, oldText, newText, oldText != null, RiskLevel.LOW);
            }

            @Override
            public String currentText(String path, String baselineText) {
                return sandbox.currentText(path, baselineText);
            }

            @Override
            public boolean proposed(String path) {
                return sandbox.hasChange(path);
            }

            @Override
            public String proposedText(String path) {
                return sandbox.proposedText(path);
            }
        };
    }

    @Test
    void createToolRecordsNewFile(@TempDir Path dir) throws IOException {
        Path root = workspace(dir);
        ArrayList<Proposal> proposals = new ArrayList<Proposal>();
        SandboxCreateFileTool tool = new SandboxCreateFileTool(new PathValidator(root),
            (path, oldText, newText) -> proposals.add(new Proposal(path, oldText, newText)));

        tool.execute(Map.of("path", "src/B.java", "content", "class B {}\n"));

        assertEquals(1, proposals.size());
        assertNull(proposals.get(0).oldText(), "新建文件没有旧内容");
        assertFalse(Files.exists(root.resolve("src/B.java")));
    }

    @Test
    void scopeBlocksFilesOutsideWhitelist(@TempDir Path dir) throws IOException {
        Path root = workspace(dir);
        AgentScope scope = AgentScope.readOnly(List.of("src/**"), List.of());
        PathValidator validator = new PathValidator(root, scope);
        SandboxEditFileTool tool = new SandboxEditFileTool(validator, SandboxSink.rejectAll());

        String result = tool.execute(Map.of("path", "secret.txt", "old_string", "top secret", "new_string", "leaked"));

        assertTrue(result.startsWith("ERROR"), result);
        assertTrue(result.contains("白名单"), result);
        assertThrows(WorkspaceViolationException.class, () -> validator.resolve("secret.txt"));
        assertTrue(validator.allows(validator.resolve("src/A.java")));
    }

    @Test
    void listDirHidesFilesOutsideScope(@TempDir Path dir) throws IOException {
        Path root = workspace(dir);
        AgentScope scope = AgentScope.readOnly(List.of("src/**"), List.of());
        ListDirTool tool = new ListDirTool(new PathValidator(root, scope));

        String result = tool.execute(Map.of("path", "."));

        assertTrue(result.contains("src/"), result);
        assertFalse(result.contains("secret.txt"), result);
    }

    @Test
    void editWithoutUniqueMatchFails(@TempDir Path dir) throws IOException {
        Path root = workspace(dir);
        Files.writeString(root.resolve("src/Dup.java"), "x\nx\n");
        SandboxEditFileTool tool = new SandboxEditFileTool(new PathValidator(root), SandboxSink.rejectAll());

        String result = tool.execute(Map.of("path", "src/Dup.java", "old_string", "x", "new_string", "y"));

        assertTrue(result.contains("无法唯一定位"), result);
    }
}
