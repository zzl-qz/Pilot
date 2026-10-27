package com.pilot.core.project;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 项目存储端口。 */
public interface ProjectRepository {

    Project create(String id, String name, List<String> folders, String baseCommit, Instant now);

    /** 兼容调用：不记录基线。 */
    default Project create(String id, String name, List<String> folders, Instant now) {
        return create(id, name, folders, null, now);
    }

    Optional<Project> find(String id);

    /** 按更新时间倒序返回全部项目。 */
    List<Project> list();

    boolean rename(String id, String name, Instant now);

    /** 整体替换绑定文件夹列表。 */
    boolean updateFolders(String id, List<String> folders, Instant now);

    /** 删除项目记录；项目下的对话由调用方决定是否级联删除。 */
    boolean delete(String id);
}
