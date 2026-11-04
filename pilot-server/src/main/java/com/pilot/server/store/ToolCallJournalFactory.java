package com.pilot.server.store;

import java.nio.file.Path;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.pilot.core.tool.ToolCallJournal;

import tools.jackson.databind.ObjectMapper;

/**
 * 按运行的工作区创建幂等账本。
 *
 * <p>账本必须知道工作区才能核对「文件现在还是不是我写的那份」，
 * 所以不能做成全局单例。内存存储模式返回空实现（没有跨重启的账本）。
 */
@Component
public class ToolCallJournalFactory {
    private final ObjectProvider<SqliteStore> store;
    private final ObjectMapper mapper;

    public ToolCallJournalFactory(ObjectProvider<SqliteStore> store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    /** 删除会话时清掉其幂等账本；没有持久化存储时什么都不做。 */
    public int deleteSession(String sessionId) {
        SqliteStore sqlite = store.getIfAvailable();
        if (sqlite == null) {
            return 0;
        }
        return new JdbcToolCallJournal(sqlite, mapper, sessionId, null).deleteBySession(sessionId);
    }

    public ToolCallJournal forWorkspace(String sessionId, Path workspace) {
        SqliteStore sqlite = store.getIfAvailable();
        if (sqlite == null || workspace == null) {
            return ToolCallJournal.none();
        }
        return new JdbcToolCallJournal(sqlite, mapper, sessionId, workspace);
    }
}
