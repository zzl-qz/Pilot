package com.pilot.server.permission;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.core.permission.PermissionRules;
import com.pilot.server.workspace.WorkspaceService;

import lombok.RequiredArgsConstructor;

/**
 * 工作区权限规则（{@code .pilot/settings.json} 的 {@code permissions} 段）的读写入口。
 *
 * <p>规则本来就只能由用户写配置文件，模型既改不了也绕不过；这里只是让界面能读和改，
 * 不必让用户手抄 JSON 语法。判定顺序仍是 deny → ask → allow，都没命中才回落权限档位。
 * 每次请求都重新读盘，因此改完立即对后续工具调用生效。
 */
@RestController
@RequestMapping("/api/permission-rules")
@RequiredArgsConstructor
public class PermissionRulesController {
    private final WorkspaceService workspaces;

    @GetMapping
    public RulesResponse get(@RequestParam("workspace") String workspace) {
        try {
            return current(workspaces.requireDirectory(workspace));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PutMapping
    public RulesResponse update(@RequestBody(required = false) RulesRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        Path workspace;
        try {
            workspace = workspaces.requireDirectory(request.workspace());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        try {
            PermissionRules.save(workspace, request.allow(), request.deny(), request.ask());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "写入权限规则失败：" + e.getMessage());
        }
        return current(workspace);
    }

    private static RulesResponse current(Path workspace) {
        PermissionRules rules = PermissionRules.loadWorkspace(workspace);
        PermissionRules inherited = PermissionRules.loadUser();
        return new RulesResponse(workspace.toString(), PermissionRules.workspaceFile(workspace).toString(),
            rules.allowRules(), rules.denyRules(), rules.askRules(), rules.loadIssues(),
            new InheritedRules(inherited.allowRules(), inherited.denyRules(), inherited.askRules()));
    }

    /**
     * @param file       工作区规则文件路径，供界面提示用户改的是哪个文件
     * @param loadIssues 读取时遇到的问题；非空说明规则没生效，界面要显示出来
     * @param inherited  用户级（{@code ~/.pilot/settings.json}）已生效的规则，只读展示，不参与回写
     */
    public record RulesResponse(String workspace, String file, List<String> allow, List<String> deny, List<String> ask,
        List<String> loadIssues, InheritedRules inherited) {
    }

    public record InheritedRules(List<String> allow, List<String> deny, List<String> ask) {
    }

    public record RulesRequest(String workspace, List<String> allow, List<String> deny, List<String> ask) {
    }
}
