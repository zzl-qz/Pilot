package com.pilot.server.controller;

import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.core.workspace.PathValidator;
import com.pilot.core.workspace.WorkspaceViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 工作区文件浏览：给左侧文件树和右侧只读预览用。
 *
 * <p>只读接口，所有路径都经过 {@link PathValidator} 限制在会话工作区内。
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionFilesController {
    /** 构建产物与运行期噪音目录，不进文件树。 */
    private static final Set<String> NOISY = Set.copyOf(List.of(
            ".git", ".gradle", ".idea", ".vscode", "node_modules", "target", "build", "dist", "out", ".next",
            ".playwright-cli", ".pilot", "logs"));
    private static final int MAX_ENTRIES = 400;
    private static final int MAX_PREVIEW_BYTES = 512 * 1024;
    /** @ 补全用的递归清单：默认 5000 个文件、12 层目录，超出就截断。 */
    private static final int MAX_FLAT_ENTRIES = 5_000;
    private static final int MAX_FLAT_LIMIT = 20_000;
    private static final int MAX_FLAT_DEPTH = 12;

    private final SessionService sessions;

    public SessionFilesController(SessionService sessions) {
        this.sessions = sessions;
    }

    /** 目录树的一层：目录在前、文件在后，按名称排序。 */
    @GetMapping("/{id}/files")
    public TreeResponse tree(@PathVariable String id, @RequestParam(name = "path", required = false) String path) {
        PathValidator paths = paths(id);
        Path root = paths.root();
        Path directory = path == null || path.isBlank() ? root : paths.resolve(path);
        if (!Files.isDirectory(directory)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不是目录: " + path);
        }

        List<Entry> entries = new ArrayList<>();
        boolean truncated = false;
        try (var stream = Files.list(directory)) {
            List<Path> children = stream
                    .filter(child -> !NOISY.contains(name(child).toLowerCase(Locale.ROOT)))
                    .sorted(Comparator
                            .comparing((Path child) -> !Files.isDirectory(child))
                            .thenComparing(child -> name(child).toLowerCase(Locale.ROOT)))
                    .toList();
            for (Path child : children) {
                if (entries.size() >= MAX_ENTRIES) {
                    truncated = true;
                    break;
                }
                boolean directoryEntry = Files.isDirectory(child);
                entries.add(new Entry(
                        name(child),
                        relative(root, child),
                        directoryEntry,
                        directoryEntry ? null : sizeOrNull(child)));
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "无法读取目录: " + path, e);
        }

        String parent = directory.equals(root) ? null : relative(root, directory.getParent());
        return new TreeResponse(relative(root, directory), parent, entries, truncated);
    }

    /**
     * 一次返回工作区内的全部文件路径，给输入框的 {@code @} 补全用。
     *
     * <p>文件树是懒加载的，只看已展开的目录会漏掉大部分文件；这里递归走一遍，遇到
     * {@code .git}、{@code node_modules} 这类目录整枝跳过（不进去枚举），避免大仓库扫一遍很慢。
     */
    @GetMapping("/{id}/files/flat")
    public FlatResponse flat(@PathVariable String id,
                             @RequestParam(name = "limit", required = false) Integer limit) {
        PathValidator paths = paths(id);
        Path root = paths.root();
        int max = limit == null || limit < 1 ? MAX_FLAT_ENTRIES : Math.min(limit, MAX_FLAT_LIMIT);
        List<String> files = new ArrayList<>();
        boolean[] truncated = {false};
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), MAX_FLAT_DEPTH,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (!dir.equals(root) && NOISY.contains(fileName(dir))) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (!attrs.isRegularFile()) {
                                return FileVisitResult.CONTINUE;
                            }
                            if (files.size() >= max) {
                                truncated[0] = true;
                                return FileVisitResult.TERMINATE;
                            }
                            files.add(relative(root, file));
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException e) {
                            // 个别目录不可读不影响其余文件
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            if (files.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "无法读取工作区文件", e);
            }
            truncated[0] = true;
        }
        files.sort(String.CASE_INSENSITIVE_ORDER);
        return new FlatResponse(files, truncated[0]);
    }

    /** 只读预览；二进制或超大文件返回标记，不返回内容。 */
    @GetMapping("/{id}/file")
    public FileResponse file(@PathVariable String id, @RequestParam("path") String path) {
        PathValidator paths = paths(id);
        Path file = paths.resolve(path);
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不是文件: " + path);
        }
        try {
            long size = Files.size(file);
            if (size > MAX_PREVIEW_BYTES) {
                return new FileResponse(path, size, false, true);
            }
            byte[] bytes = Files.readAllBytes(file);
            if (isBinary(bytes)) {
                return new FileResponse(path, size, true, false);
            }
            return new FileResponse(path, size, false, false, new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "无法读取文件: " + path, e);
        }
    }

    /** 越界路径属于调用方错误，返回 400 而不是 500。 */
    @ExceptionHandler({WorkspaceViolationException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> onInvalidPath(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() == null ? "非法路径" : e.getMessage()));
    }

    private PathValidator paths(String sessionId) {
        Session session = sessions.find(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在: " + sessionId));
        if (session.workspace() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该会话没有绑定工作区");
        }
        return new PathValidator(Path.of(session.workspace()));
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static String name(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? path.toString() : fileName.toString();
    }

    /** 目录名统一小写后再和噪音目录比对。 */
    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
    }

    private static Long sizeOrNull(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, 8000);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /** @param path 相对工作区的路径，根目录为空字符串 */
    public record TreeResponse(String path, String parent, List<Entry> entries, boolean truncated) { }

    public record Entry(String name, String path, boolean directory, Long size) { }

    public record FlatResponse(List<String> files, boolean truncated) { }

    public record FileResponse(String path, long size, boolean binary, boolean truncated, String content) {
        public FileResponse(String path, long size, boolean binary, boolean truncated) {
            this(path, size, binary, truncated, null);
        }
    }
}
