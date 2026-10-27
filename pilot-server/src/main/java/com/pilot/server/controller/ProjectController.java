package com.pilot.server.controller;

import com.pilot.core.project.Project;
import com.pilot.core.project.ProjectRepository;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.session.SessionCleanupService;
import com.pilot.server.workspace.WorkspaceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 项目：对话的上级容器，绑定一个或多个本地文件夹。
 *
 * <p>前端先建项目，再在项目下新建对话；文件夹允许随时增删，
 * 已经存在的对话保留它自己的当前工作区，不会被改写。
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {
    private static final int MAX_FOLDERS = 12;

    private final ProjectRepository projects;
    private final SessionService sessions;
    private final SessionCleanupService cleanup;
    private final WorkspaceService workspaces;
    private final AgentProperties agentProperties;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ProjectController(ProjectRepository projects, SessionService sessions,
                             SessionCleanupService cleanup, WorkspaceService workspaces,
                             AgentProperties agentProperties) {
        this(projects, sessions, cleanup, workspaces, agentProperties, Clock.systemUTC());
    }

    ProjectController(ProjectRepository projects, SessionService sessions, SessionCleanupService cleanup,
                      WorkspaceService workspaces, AgentProperties agentProperties, Clock clock) {
        this.projects = projects;
        this.sessions = sessions;
        this.cleanup = cleanup;
        this.workspaces = workspaces;
        this.agentProperties = agentProperties;
        this.clock = clock;
    }

    @GetMapping
    public List<ProjectResponse> list() {
        return projects.list().stream().map(this::response).toList();
    }

    @GetMapping("/{id}")
    public ProjectResponse get(@PathVariable String id) {
        return response(require(id));
    }

    @PostMapping
    public ProjectResponse create(@RequestBody(required = false) CreateProjectRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        List<String> folders = folders(request.folders());
        if (folders.isEmpty()) {
            // 没显式绑定文件夹时退化成默认工作区，避免建出「无目录可用」的项目
            folders = List.of(agentProperties.workspace());
        }
        Project project = projects.create("prj_" + UUID.randomUUID(), name(request.name()), folders,
                gitHead(folders.get(0)), clock.instant());
        return response(project);
    }

    @PatchMapping("/{id}")
    public ProjectResponse rename(@PathVariable String id, @RequestBody(required = false) RenameProjectRequest request) {
        require(id);
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name must not be blank");
        }
        projects.rename(id, request.name().strip(), clock.instant());
        return response(require(id));
    }

    /** 整体替换绑定的文件夹；已存在的对话不会被迁移，保持各自的当前工作区。 */
    @PutMapping("/{id}/folders")
    public ProjectResponse updateFolders(@PathVariable String id,
                                         @RequestBody(required = false) FoldersRequest request) {
        require(id);
        List<String> folders = folders(request == null ? null : request.folders());
        if (folders.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "至少保留一个文件夹");
        }
        if (!projects.updateFolders(id, folders, clock.instant())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "project not found: " + id);
        }
        return response(require(id));
    }

    /**
     * 删除项目。
     *
     * @param deleteSessions 默认 true：项目下的对话一并删除；false 时对话改为未分组
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id,
                                       @RequestParam(name = "deleteSessions", defaultValue = "true")
                                       boolean deleteSessions) {
        if (projects.find(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "project not found: " + id);
        }
        List<Session> owned = sessions.listByProject(id);
        if (deleteSessions) {
            owned.forEach(session -> cleanup.delete(session.id()));
        }
        projects.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** 文件夹路径逐个校验：必须是存在的目录，否则整个请求拒绝，避免建出一个点不开的项目。 */
    private List<String> folders(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        if (raw.size() > MAX_FOLDERS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "一个项目最多绑定 " + MAX_FOLDERS + " 个文件夹");
        }
        List<String> resolved = new ArrayList<>();
        for (String folder : raw) {
            if (folder == null || folder.isBlank()) {
                continue;
            }
            try {
                Path path = workspaces.requireDirectory(folder);
                String text = path.toString();
                if (!resolved.contains(text)) {
                    resolved.add(text);
                }
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
            }
        }
        return List.copyOf(resolved);
    }

    private static String name(String raw) {
        return raw == null || raw.isBlank() ? "未命名项目" : raw.strip();
    }

    /**
     * 读取仓库当前 HEAD 作为项目基线。
     *
     * <p>只读 {@code .git/HEAD} 与对应的 ref 文件，不启动 git 进程：
     * 建项目是同步请求，多 fork 一次进程不值得。拿不到就返回 null（不是 git 仓库也不算错）。
     */
    private static String gitHead(String folder) {
        try {
            Path root = Path.of(folder);
            Path dotGit = root.resolve(".git");
            // 工作区可能在子目录里：向上找到仓库根
            for (int depth = 0; depth < 6 && !java.nio.file.Files.isDirectory(dotGit); depth++) {
                root = root.getParent();
                if (root == null) {
                    return null;
                }
                dotGit = root.resolve(".git");
            }
            if (!java.nio.file.Files.isDirectory(dotGit)) {
                return null;
            }
            String head = java.nio.file.Files.readString(dotGit.resolve("HEAD")).strip();
            String prefix = "ref: ";
            if (!head.startsWith(prefix)) {
                return head.isEmpty() ? null : head;
            }
            Path ref = dotGit.resolve(head.substring(prefix.length()));
            return java.nio.file.Files.isRegularFile(ref)
                    ? java.nio.file.Files.readString(ref).strip()
                    : null;
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    private Project require(String id) {
        return projects.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "project not found: " + id));
    }

    private ProjectResponse response(Project project) {
        List<Session> owned = sessions.listByProject(project.id());
        Instant lastActive = owned.isEmpty() ? project.updatedAt() : owned.get(0).createdAt();
        // 空白对话单列：它不进历史列表，但界面要靠它决定「新建对话」按钮的行为
        Session blank = owned.stream()
                .filter(session -> session.isBlank() && !session.archived())
                .findFirst()
                .orElse(null);
        return new ProjectResponse(project.id(), project.name(), project.folders(), project.baseCommit(),
                owned.size(), blank == null ? null : blank.id(), blank == null ? 0 : 1,
                project.createdAt(), project.updatedAt(), lastActive);
    }

    public record CreateProjectRequest(String name, List<String> folders) {
    }

    public record RenameProjectRequest(String name) {
    }

    public record FoldersRequest(List<String> folders) {
    }

    /**
     * @param folders      绑定的绝对路径，第一个是新建对话的默认工作区
     * @param baseCommit   建项目时的仓库基线；不是 git 仓库时为空
     * @param sessionCount 项目下的对话数（含空白对话）
     * @param blankSessionId 项目当前那条空白对话；没有则为空
     */
    public record ProjectResponse(String id, String name, List<String> folders, String baseCommit,
                                  int sessionCount, String blankSessionId, int blankCount,
                                  Instant createdAt, Instant updatedAt, Instant lastActiveAt) {
    }
}
