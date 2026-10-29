package com.pilot.server.controller;

import com.pilot.core.session.SessionService;
import com.pilot.core.skill.SkillManifest;
import com.pilot.core.skill.SkillProblem;
import com.pilot.server.session.SkillService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

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
    private final SessionService sessions;

    public SkillsController(SkillService skills, SessionService sessions) {
        this.skills = skills;
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
            workspace = sessions.find(sessionId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "session not found: " + sessionId))
                    .workspace();
        }
        var catalog = skills.catalogFor(workspace);
        List<SkillPayload> available = catalog.list().stream().map(SkillPayload::of).toList();
        return new SkillsResponse(available, catalog.invalid(),
                skills.projectRootFor(workspace).toString(),
                skills.userRootOf().toString());
    }

    public record SkillPayload(String name, String description, String command, String argumentHint,
                               List<String> allowedTools, String source, String directory) {
        static SkillPayload of(SkillManifest manifest) {
            return new SkillPayload(manifest.name(), manifest.description(), manifest.command(),
                    manifest.argumentHint(), manifest.allowedTools(),
                    manifest.source().name().toLowerCase(Locale.ROOT),
                    manifest.directory().toString());
        }
    }

    public record SkillsResponse(List<SkillPayload> available, List<SkillProblem> invalid,
                                 String projectRoot, String userRoot) { }
}
