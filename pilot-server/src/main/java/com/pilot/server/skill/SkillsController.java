package com.pilot.server.skill;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.core.session.SessionService;
import com.pilot.core.skill.SkillManifest;
import com.pilot.core.skill.SkillProblem;
import com.pilot.server.common.web.ApiErrors;
import com.pilot.server.common.web.SessionLookup;

/**
 * 技能目录查询：前端 `/` 快捷命令菜单与设置页从这里拉取清单。
 *
 * <p>技能没有写操作 API——安装就是往目录放文件（项目级 {@code <workspace>/.pilot/skills}、
 * 用户级 {@code <dataDir>/skills}），删除就是删目录；每次请求重新扫描，改完即生效。
 * {@code invalid} 列表把校验失败的技能亮出来（名称与原因），帮用户修好自己的技能。
 */
@RestController
@RequestMapping("/api/skills")
public class SkillsController {
    private final SkillService skills;
    private final SkillInstallService installer;
    private final SessionService sessions;

    public SkillsController(SkillService skills, SkillInstallService installer, SessionService sessions) {
        this.skills = skills;
        this.installer = installer;
        this.sessions = sessions;
    }

    /**
     * 技能清单。技能目录按工作区解析（项目级技能随仓库走），因此以会话为锚点；
     * 不带 sessionId 时按默认工作区返回（用于新建会话前的展示）。
     * {@code projectRoot}/{@code userRoot} 是两个安装位置，供设置页引导用户往哪放。
     */
    @GetMapping
    public SkillsResponse list(@RequestParam(name = "sessionId", required = false) String sessionId) {
        String workspace = null;
        if (sessionId != null && !sessionId.isBlank()) {
            workspace = SessionLookup.require(sessions, sessionId).workspace();
        }
        var catalog = skills.catalogFor(workspace);
        List<SkillPayload> available = catalog.list().stream().map(SkillPayload::of).toList();
        return new SkillsResponse(available, catalog.invalid(), skills.projectRootFor(workspace).toString(),
            skills.userRootOf().toString());
    }

    public record SkillPayload(String name, String description, String command, String argumentHint,
        List<String> allowedTools, String source, String directory) {
        static SkillPayload of(SkillManifest manifest) {
            return new SkillPayload(manifest.name(), manifest.description(), manifest.command(),
                manifest.argumentHint(), manifest.allowedTools(), manifest.source().name().toLowerCase(Locale.ROOT),
                manifest.directory().toString());
        }
    }

    public record SkillsResponse(List<SkillPayload> available, List<SkillProblem> invalid, String projectRoot,
        String userRoot) {
    }

    /**
     * 预览安装来源：git 仓库或本机目录取到暂存区并解析候选（不安装）。
     * token 在 10 分钟内可用于 install；安装什么由后续请求明确勾选。
     */
    @PostMapping("/preview")
    public ResponseEntity<PreviewResponse> preview(@RequestBody(required = false) PreviewRequest request) {
        var preview = installer.preview(request == null ? null : request.source(),
            resolveWorkspace(request == null ? null : request.sessionId()), request == null ? null : request.ref(),
            request == null ? null : request.path());
        return ResponseEntity
            .ok(new PreviewResponse(preview.token(), preview.kind(), preview.message(),
                preview
                    .candidates().stream().map(candidate -> new CandidatePayload(candidate.name(),
                        candidate.description(), candidate.command(), candidate.valid(), candidate.reason()))
                    .toList()));
    }

    /**
     * 安装预览中选中的技能。逐技能出结果：installed / conflict / invalid / error；
     * conflict 不是协议错误——重发同名技能 + overwrite=true 即覆盖安装。
     */
    @PostMapping("/install")
    public ResponseEntity<InstallResponse> install(@RequestBody(required = false) InstallRequest request) {
        if (request == null || request.skills() == null || request.skills().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择要安装的技能");
        }
        boolean project;
        if ("project".equals(request.target())) {
            project = true;
        } else if ("user".equals(request.target())) {
            project = false;
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "target 只能是 project 或 user");
        }
        var result = installer.install(request.token(), request.skills(), project,
            resolveWorkspace(request.sessionId()), Boolean.TRUE.equals(request.overwrite()));
        return ResponseEntity.ok(new InstallResponse(result.results().stream()
            .map(outcome -> new OutcomePayload(outcome.name(), outcome.status(), outcome.reason())).toList()));
    }

    /** 安装器失败统一 400 带中文原因（include-message: always，前端直接展示）。 */
    @ExceptionHandler(SkillInstallService.InstallException.class)
    public ResponseEntity<Map<String, String>> onInstallFailure(SkillInstallService.InstallException e) {
        return ApiErrors.message(HttpStatus.BAD_REQUEST, Objects.requireNonNullElse(e.getMessage(), "安装失败"));
    }

    /** sessionId 非空时必须存在（与 GET 同口径）；缺省按默认工作区解析。 */
    private String resolveWorkspace(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return SessionLookup.require(sessions, sessionId).workspace();
    }

    public record PreviewRequest(String source, String sessionId, String ref, String path) {
        public PreviewRequest(String source, String sessionId) {
            this(source, sessionId, null, null);
        }
    }

    public record PreviewResponse(String token, String kind, String message, List<CandidatePayload> candidates) {
    }

    public record CandidatePayload(String name, String description, String command, boolean valid, String reason) {
    }

    public record InstallRequest(String token, List<String> skills, String target, String sessionId,
        Boolean overwrite) {
    }

    public record OutcomePayload(String name, String status, String reason) {
    }

    public record InstallResponse(List<OutcomePayload> results) {
    }
}
