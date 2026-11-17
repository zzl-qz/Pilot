package com.pilot.core.quality;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.workspace.AgentScope;
import com.pilot.core.workspace.PathValidator;

class AuditCodebaseToolTest {

    @Test
    void exposesParallelReadOnlySemanticsAndFiltersByFocus(@TempDir Path workspace) throws IOException {
        Path file = workspace.resolve("src/main/AppService.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            import java.io.FileInputStream;
            import java.util.List;
            public class AppService {
                public void run(List<String> ids) throws Exception {
                    int magicTtl = 43200;
                    FileInputStream unclosed = new FileInputStream("a.txt");
                    for (String id : ids) {
                        userRepo.findById(id);
                    }
                    System.out.println(magicTtl + unclosed.available());
                }
            }
            """);

        AuditCodebaseTool tool = new AuditCodebaseTool(new PathValidator(workspace));
        assertTrue(tool.readOnly());
        assertTrue(tool.readOnly(Map.of()));
        assertTrue(tool.parallelSafe());
        assertFalse(tool.description().isBlank());

        String allReport = tool.execute(Map.of());
        assertTrue(allReport.contains("扫描生产文件: 1"), allReport);
        assertTrue(allReport.contains("magic-number"), allReport);
        assertTrue(allReport.contains("loop-io-or-query"), allReport);
        assertTrue(allReport.contains("unclosed-resource"), allReport);

        String perfOnly = tool.execute(Map.of("path", "src/main", "focus", "performance", "max_findings", 5));
        assertTrue(perfOnly.contains("loop-io-or-query"), perfOnly);
        assertFalse(perfOnly.contains("[magic-number]"), perfOnly);

        String boundaryOnly = tool.execute(Map.of("path", "src/main/AppService.java", "focus", "boundary"));
        assertTrue(boundaryOnly.contains("unclosed-resource"), boundaryOnly);
        assertFalse(boundaryOnly.contains("[loop-io-or-query]"), boundaryOnly);
    }

    @Test
    void rejectsInvalidArgumentsMissingPathsAndOutOfScopeDirectories(@TempDir Path workspace) throws IOException {
        Files.createDirectories(workspace.resolve("allowed"));
        Files.createDirectories(workspace.resolve("secret"));
        Files.writeString(workspace.resolve("secret/Internal.java"), "class Internal { int k = 9999; }\n");

        AuditCodebaseTool fullTool = new AuditCodebaseTool(new PathValidator(workspace));
        assertTrue(fullTool.execute(Map.of("focus", "unknown")).startsWith("ERROR:"));
        assertTrue(fullTool.execute(Map.of("max_findings", 0)).startsWith("ERROR:"));
        assertTrue(fullTool.execute(Map.of("max_findings", "abc")).startsWith("ERROR:"));
        assertTrue(fullTool.execute(Map.of("path", "missing/dir")).startsWith("ERROR:"));
        assertTrue(fullTool.execute(Map.of("path", "../outside")).startsWith("ERROR:"));

        AgentScope restricted = AgentScope.readOnly(List.of("allowed/**"), List.of("secret/**"));
        AuditCodebaseTool scopedTool = new AuditCodebaseTool(new PathValidator(workspace, restricted));
        assertTrue(scopedTool.execute(Map.of("path", "secret")).startsWith("ERROR:"));
        String rootScoped = scopedTool.execute(Map.of());
        assertTrue(rootScoped.contains("扫描生产文件: 0"), rootScoped);
    }
}
