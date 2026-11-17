package com.pilot.core.quality;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.pilot.core.quality.CodeQualityCheck.Finding;

class PerformanceBoundaryScannerTest {

    @Test
    void detectsLoopHeavyAllocNPlusOneAndStringConcatInLoops() {
        String smellyJava = """
            package demo;
            import java.text.SimpleDateFormat;
            import java.util.List;
            import java.util.regex.Pattern;

            public class OrderBatchProcessor {
                public String processAll(List<String> orderIds) {
                    String csv = "";
                    for (String id : orderIds) {
                        Pattern idPattern = Pattern.compile("^[A-Z0-9]+$");
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
                        Order order = orderRepository.findById(id).orElse(null);
                        csv += id + ",";
                    }
                    return csv;
                }
            }
            """;

        List<Finding> findings = PerformanceBoundaryScanner.scanFile("src/main/OrderBatchProcessor.java", smellyJava);
        Set<String> kinds = findings.stream().map(Finding::kind).collect(Collectors.toSet());

        assertTrue(kinds.contains(PerformanceBoundaryScanner.KIND_LOOP_HEAVY_ALLOC), findings.toString());
        assertTrue(kinds.contains(PerformanceBoundaryScanner.KIND_LOOP_IO_QUERY), findings.toString());
        assertTrue(findings.stream().anyMatch(f -> f.message().contains("StringBuilder")), findings.toString());
    }

    @Test
    void detectsPythonAndTypeScriptLoopIoAndUnboundedSql() {
        String pyCode = """
            def sync_users(user_ids):
                for uid in user_ids:
                    resp = requests.get("https://api.example.com/users/" + uid)
                    cursor.execute("SELECT * FROM audit_logs")
            """;
        List<Finding> pyFindings = PerformanceBoundaryScanner.scanFile("src/sync_service.py", pyCode);
        Set<String> pyKinds = pyFindings.stream().map(Finding::kind).collect(Collectors.toSet());
        assertTrue(pyKinds.contains(PerformanceBoundaryScanner.KIND_LOOP_IO_QUERY), pyFindings.toString());
        assertTrue(pyKinds.contains(PerformanceBoundaryScanner.KIND_UNBOUNDED_READ), pyFindings.toString());

        String tsCode = """
            export async function loadItems(ids: string[]) {
                for (const id of ids) {
                    const re = new RegExp(`^item_${id}$`);
                    await fetch(`/api/items/${id}`);
                }
            }
            """;
        List<Finding> tsFindings = PerformanceBoundaryScanner.scanFile("src/loader.ts", tsCode);
        Set<String> tsKinds = tsFindings.stream().map(Finding::kind).collect(Collectors.toSet());
        assertTrue(tsKinds.contains(PerformanceBoundaryScanner.KIND_LOOP_HEAVY_ALLOC), tsFindings.toString());
        assertTrue(tsKinds.contains(PerformanceBoundaryScanner.KIND_LOOP_IO_QUERY), tsFindings.toString());
    }

    @Test
    void detectsUnclosedResourcesUnboundedReadsAndUnsafeGets() {
        String hazardous =
            """
                package demo;
                import java.io.FileInputStream;
                import java.nio.file.Files;
                import java.nio.file.Path;
                import java.util.List;
                import java.util.Optional;

                public class FileLoader {
                    public String loadFirst(Path dir, Path target, List<String> args, Optional<String> optToken) throws Exception {
                        byte[] all = Files.readAllBytes(target);
                        FileInputStream raw = new FileInputStream(target.toFile());
                        long count = Files.list(dir).count();
                        int limit = Integer.parseInt(args.get(0));
                        String found = args.stream().findFirst().get();
                        return optToken.get() + all.length + count + limit + found + raw.available();
                    }
                }
                """;

        List<Finding> findings = PerformanceBoundaryScanner.scanFile("src/main/FileLoader.java", hazardous);
        Set<String> kinds = findings.stream().map(Finding::kind).collect(Collectors.toSet());

        assertTrue(kinds.contains(PerformanceBoundaryScanner.KIND_UNBOUNDED_READ), findings.toString());
        assertTrue(kinds.contains(PerformanceBoundaryScanner.KIND_UNCLOSED_RESOURCE), findings.toString());
        assertTrue(kinds.contains(PerformanceBoundaryScanner.KIND_UNSAFE_GET), findings.toString());
    }

    @Test
    void exemptsCleanIdiomaticCodeWithoutFalsePositives() {
        String cleanJava = """
            package demo;
            import java.io.BufferedReader;
            import java.io.InputStream;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.util.List;
            import java.util.Optional;
            import java.util.concurrent.atomic.AtomicBoolean;
            import java.util.function.Supplier;
            import java.util.regex.Pattern;
            import java.util.stream.Stream;

            public class CleanService {
                private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z0-9_]+$");
                private static final long MAX_FILE_BYTES = 1048576L;

                public String handle(Path dir, Path file, List<String> items, Optional<String> optName,
                    AtomicBoolean cancelled, Supplier<String> fallback, ObjectMapper mapper) throws Exception {
                    if (cancelled.get()) {
                        return fallback.get();
                    }
                    int count = 0;
                    StringBuilder out = new StringBuilder();
                    for (String item : items) {
                        if (CODE_PATTERN.matcher(item).matches()) {
                            count += 1;
                            out.append(mapper.writeValueAsString(item));
                        }
                    }
                    if (Files.size(file) <= MAX_FILE_BYTES) {
                        byte[] safeBytes = Files.readAllBytes(file);
                        out.append(safeBytes.length);
                    }
                    try (Stream<Path> children = Files.list(dir);
                        InputStream in = Files.newInputStream(file);
                        BufferedReader reader = Files.newBufferedReader(file)) {
                        out.append(children.count()).append(in.available()).append(reader.readLine());
                    }
                    int parsed = 0;
                    try {
                        parsed = Integer.parseInt("42");
                    } catch (NumberFormatException ignored) {
                        parsed = 0;
                    }
                    String sql = "SELECT * FROM orders ORDER BY id DESC LIMIT 20";
                    String first = items.isEmpty() ? "" : items.get(0);
                    String resolved = optName.isPresent() ? optName.get() : first;
                    return out.append(count).append(parsed).append(sql).append(resolved).toString();
                }
            }
            """;

        List<Finding> findings = PerformanceBoundaryScanner.scanFile("src/main/CleanService.java", cleanJava);
        assertTrue(findings.isEmpty(), "Expected 0 false positives, got: " + findings);
    }

    @Test
    void integratesIncrementallyIntoCodeQualityCheck() {
        String before = """
            class UserService {
                void sync(java.util.List<String> ids) {
                }
            }
            """;
        String after = """
            class UserService {
                void sync(java.util.List<String> ids) {
                    for (String id : ids) {
                        userRepository.findById(id);
                    }
                }
            }
            """;

        String summary = CodeQualityCheck.summary("src/main/UserService.java", before, after);
        assertFalse(summary.isBlank());
        assertTrue(summary.contains("N+1"), summary);
    }
}
