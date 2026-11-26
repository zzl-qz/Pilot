package com.pilot.core.tool.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.tool.ReadTracker;
import com.pilot.core.tool.SnapshotSink;
import com.pilot.core.workspace.PathValidator;

class MoveFileToolTest {
    @TempDir
    Path root;

    @Test
    void allowsCaseOnlyRenameAndUpdatesActualFilename() throws Exception {
        Path source = Files.writeString(root.resolve("foo.ts"), "content");
        PathValidator paths = new PathValidator(root);
        ReadTracker reads = new ReadTracker();
        reads.observed(source);
        MoveFileTool tool = new MoveFileTool(paths, SnapshotSink.none(), reads);
        var result = tool.executeResult(Map.of("source", "foo.ts", "destination", "Foo.ts"));
        assertFalse(result.text().startsWith("ERROR:"), result.text());
        Path target = root.resolve("Foo.ts");
        assertTrue(Files.exists(target));
        assertEquals("Foo.ts", target.toRealPath().getFileName().toString());
        assertEquals("content", Files.readString(target));
    }

    @Test
    void rejectsIdenticalPathAndMissingSourceBeforeSameFileCheck() throws Exception {
        Files.writeString(root.resolve("existing.ts"), "keep");
        MoveFileTool tool = new MoveFileTool(new PathValidator(root), SnapshotSink.none(), new ReadTracker());

        var same = tool.executeResult(Map.of("source", "existing.ts", "destination", "existing.ts"));
        assertTrue(same.text().contains("源路径与目标路径相同"), same.text());

        var missing = tool.executeResult(Map.of("source", "missing.ts", "destination", "existing.ts"));
        assertTrue(missing.text().contains("源文件不存在"), missing.text());
    }
}
