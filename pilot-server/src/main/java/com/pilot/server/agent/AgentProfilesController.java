package com.pilot.server.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.core.multi.profile.AgentProfile;
import com.pilot.core.multi.profile.AgentProfileCatalog;
import com.pilot.core.session.SessionService;
import com.pilot.server.session.lookup.SessionLookup;

import lombok.RequiredArgsConstructor;

/**
 * 子代理角色清单查询与用户级角色增删改接口。
 *
 * <p>项目级（{@code <workspace>/.pilot/agents}）与内置角色只读展示；
 * 写操作仅限用户级目录（{@code <pilot.data.dir>/agents} 或 {@code PILOT_AGENTS_DIR}），
 * 且不允许覆盖符号链接或越界写入。
 */
@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentProfilesController {
    private final AgentProfileService profiles;
    private final SessionService sessions;

    @GetMapping
    public ProfilesResponse list(@RequestParam(name = "sessionId", required = false) String sessionId) {
        String workspace = SessionLookup.workspaceOrNull(sessions, sessionId);
        Path projectRoot = profiles.projectRootFor(workspace);
        Path userRoot = profiles.userRoot();
        boolean rootsOverlap = projectRoot.equals(userRoot);
        AgentProfileCatalog all = profiles.catalogFor(workspace);
        AgentProfileCatalog project = AgentProfileCatalog.scan(List.of(projectRoot));
        AgentProfileCatalog user = AgentProfileCatalog.scan(List.of(userRoot));
        List<ProfilePayload> result = all.profiles().values().stream().map(profile -> {
            String source = resolveSource(profile.name(), project, user, rootsOverlap);
            String content = readContent(source, profile.name(), projectRoot, userRoot);
            boolean hasUserOverride = user.find(profile.name()).isPresent();
            return ProfilePayload.of(profile, source, content, hasUserOverride);
        }).sorted(Comparator.comparing(ProfilePayload::name)).toList();
        return new ProfilesResponse(result, all.invalid(), projectRoot.toString(), userRoot.toString());
    }

    @PutMapping("/{name}")
    public ProfilePayload save(@PathVariable String name, @RequestBody(required = false) ProfileRequest request) {
        validateName(name);
        if (request == null || request.content() == null) {
            throw bad("代理定义不能为空");
        }
        AgentProfile profile;
        try {
            profile = AgentProfileCatalog.parseProfile(name, request.content());
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
        Path root = profiles.userRoot().toAbsolutePath().normalize();
        Path target = resolveProfileFile(root, name);
        try {
            Files.createDirectories(root);
            if (Files.isSymbolicLink(target)) {
                throw bad("不允许覆盖符号链接代理");
            }
            Files.writeString(target, request.content(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "保存用户级代理失败", e);
        }
        return ProfilePayload.of(profile, "user", request.content(), true);
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        validateName(name);
        Path root = profiles.userRoot().toAbsolutePath().normalize();
        Path target = resolveProfileFile(root, name);
        try {
            if (Files.isSymbolicLink(target)) {
                throw bad("不允许删除符号链接代理");
            }
            if (!Files.deleteIfExists(target)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户级代理不存在：" + name);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "删除用户级代理失败", e);
        }
        return ResponseEntity.noContent().build();
    }

    private static String resolveSource(String name, AgentProfileCatalog project, AgentProfileCatalog user,
        boolean rootsOverlap) {
        if (!rootsOverlap && project.find(name).isPresent()) {
            return "project";
        }
        if (user.find(name).isPresent()) {
            return "user";
        }
        return "builtin";
    }

    private static String readContent(String source, String name, Path projectRoot, Path userRoot) {
        if ("project".equals(source)) {
            return readMarkdown(projectRoot, name);
        }
        if ("user".equals(source)) {
            return readMarkdown(userRoot, name);
        }
        return AgentProfileCatalog.readBuiltinContent(name);
    }

    private static String readMarkdown(Path root, String name) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path target = normalizedRoot.resolve(name + ".md").normalize();
        if (!normalizedRoot.equals(target.getParent())) {
            return "";
        }
        try {
            if (Files.isRegularFile(target) && !Files.isSymbolicLink(target)) {
                return Files.readString(target, StandardCharsets.UTF_8);
            }
            return "";
        } catch (IOException e) {
            return "";
        }
    }

    private static Path resolveProfileFile(Path normalizedRoot, String name) {
        Path target = normalizedRoot.resolve(name + ".md").normalize();
        if (!normalizedRoot.equals(target.getParent())) {
            throw bad("代理名称无效");
        }
        return target;
    }

    private static void validateName(String name) {
        if (name == null || !AgentProfileCatalog.VALID_NAME.matcher(name).matches()) {
            throw bad("代理名称必须匹配 ^[a-z][a-z0-9-]{0,63}$");
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record ProfileRequest(String content) {
    }

    public record ProfilesResponse(List<ProfilePayload> available, List<AgentProfileCatalog.AgentProblem> invalid,
        String projectRoot, String userRoot) {
    }

    public record ProfilePayload(String name, String description, List<String> tools, List<String> files,
        List<String> denyFiles, List<String> commands, List<String> denyCommands, boolean writable, String source,
        String content, boolean hasUserOverride) {
        static ProfilePayload of(AgentProfile p, String source, String content, boolean hasUserOverride) {
            return new ProfilePayload(p.name(), p.description(), p.tools(), p.files(), p.denyFiles(), p.commands(),
                p.denyCommands(), p.writable(), source, content, hasUserOverride);
        }
    }
}
