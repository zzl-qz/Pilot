package com.pilot.server.session;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.pilot.core.multi.AgentProfileCatalog;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.PilotDataDir;

/**
 * 自定义子代理目录的装配点（两级目录，语义与 {@link SkillService} 同构）。
 *
 * <p>项目级 {@code <workspace>/.pilot/agents}（随仓库走、可提交进项目 git）、
 * 用户级 {@code <pilot.data.dir>/agents}（本机个人库）——同名时项目级遮蔽用户级；
 * 每次 run 重新扫描，改定义随下次运行生效。代理是「角色 + 权限视图」的预设，
 * 激活全部走既有工具与权限模型，不引入新的执行路径。
 */
@Component
public class AgentProfileService {

    private final AgentProperties agentProperties;
    private final PilotDataDir dataDir;

    public AgentProfileService(AgentProperties agentProperties, PilotDataDir dataDir) {
        this.agentProperties = agentProperties;
        this.dataDir = dataDir;
    }

    /** 按工作区扫描两级代理目录。 */
    public AgentProfileCatalog catalogFor(String workspace) {
        List<Path> roots = new ArrayList<>();
        roots.add(projectRootFor(workspace));
        roots.add(userRoot());
        return AgentProfileCatalog.scan(roots);
    }

    /** 项目级代理目录固定为 {@code <workspace>/.pilot/agents}：目录即协议，不设配置。 */
    public Path projectRootFor(String workspace) {
        return resolve(workspace).resolve(".pilot").resolve("agents");
    }

    /** 用户级代理目录：环境变量 {@code PILOT_AGENTS_DIR} > {@code <dataDir>/agents}。 */
    public Path userRoot() {
        String fromEnv = System.getenv("PILOT_AGENTS_DIR");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Path.of(fromEnv.strip()).toAbsolutePath().normalize();
        }
        return dataDir.file("agents");
    }

    private Path resolve(String workspace) {
        String target = workspace == null || workspace.isBlank() ? agentProperties.workspace() : workspace;
        return Path.of(target).toAbsolutePath().normalize();
    }
}
