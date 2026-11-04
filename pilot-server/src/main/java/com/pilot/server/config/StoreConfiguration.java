package com.pilot.server.config;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.pilot.core.project.InMemoryProjectRepository;
import com.pilot.core.project.ProjectRepository;
import com.pilot.core.session.ConversationRepository;
import com.pilot.core.session.InMemoryConversationRepository;
import com.pilot.core.session.InMemoryPlanRepository;
import com.pilot.core.session.InMemoryRunCheckpointRepository;
import com.pilot.core.session.InMemorySessionRepository;
import com.pilot.core.session.PlanRepository;
import com.pilot.core.session.RunCheckpointRepository;
import com.pilot.core.session.RunRepository;
import com.pilot.core.session.SessionRepository;
import com.pilot.server.store.EventCodec;
import com.pilot.server.store.InMemorySettingsStore;
import com.pilot.server.store.JdbcConversationRepository;
import com.pilot.server.store.JdbcPlanRepository;
import com.pilot.server.store.JdbcProjectRepository;
import com.pilot.server.store.JdbcRunCheckpointRepository;
import com.pilot.server.store.JdbcRunRepository;
import com.pilot.server.store.JdbcSessionRepository;
import com.pilot.server.store.JdbcSettingsStore;
import com.pilot.server.store.QueuedMessageRepository;
import com.pilot.server.store.SettingsStore;
import com.pilot.server.store.SqliteStore;

import tools.jackson.databind.ObjectMapper;

/**
 * 存储装配：{@code pilot.store=sqlite}（默认，落盘到项目下的 {@code .pilot/pilot.db}）或 {@code memory}（进程内）。
 * 测试使用 memory，避免污染本地数据库。
 */
@Configuration(proxyBeanMethods = false)
public class StoreConfiguration {

    @Bean
    @ConditionalOnProperty(name = "pilot.store", havingValue = "sqlite", matchIfMissing = true)
    SqliteStore sqliteStore(PilotDataDir data, @Value("${pilot.store.path:}") String configured) {
        Path path = configured == null || configured.isBlank() ? data.file("pilot.db") : Path.of(configured);
        return new SqliteStore(path);
    }

    @Bean
    @ConditionalOnProperty(name = "pilot.store", havingValue = "sqlite", matchIfMissing = true)
    SessionRepository jdbcSessionRepository(SqliteStore store, EventCodec codec) {
        return new JdbcSessionRepository(store, codec);
    }

    @Bean
    @ConditionalOnProperty(name = "pilot.store", havingValue = "memory")
    SessionRepository inMemorySessionRepository() {
        return new InMemorySessionRepository();
    }

    @Bean
    @ConditionalOnProperty(name = "pilot.store", havingValue = "sqlite", matchIfMissing = true)
    RunRepository jdbcRunRepository(SqliteStore store) {
        return new JdbcRunRepository(store);
    }

    @Bean
    @ConditionalOnProperty(name = "pilot.store", havingValue = "sqlite", matchIfMissing = true)
    ProjectRepository jdbcProjectRepository(SqliteStore store) {
        return new JdbcProjectRepository(store);
    }

    @Bean
    @ConditionalOnProperty(name = "pilot.store", havingValue = "memory")
    ProjectRepository inMemoryProjectRepository() {
        return new InMemoryProjectRepository();
    }

    /** 对话记忆与会话事件分开存：事件是审计日志，记忆才是模型看到的上下文。 */
    @Bean
    @ConditionalOnProperty(name = "pilot.store", havingValue = "sqlite", matchIfMissing = true)
    ConversationRepository jdbcConversationRepository(SqliteStore store, ObjectMapper mapper) {
        return new JdbcConversationRepository(store, mapper);
    }

    @Bean
    @ConditionalOnProperty(name = "pilot.store", havingValue = "memory")
    ConversationRepository inMemoryConversationRepository() {
        return new InMemoryConversationRepository();
    }

    @Bean
    @ConditionalOnProperty(name = "pilot.store", havingValue = "sqlite", matchIfMissing = true)
    PlanRepository jdbcPlanRepository(SqliteStore store) {
        return new JdbcPlanRepository(store);
    }

    @Bean
    @ConditionalOnProperty(name = "pilot.store", havingValue = "memory")
    PlanRepository inMemoryPlanRepository() {
        return new InMemoryPlanRepository();
    }

    @Bean
    @ConditionalOnProperty(name = "pilot.store", havingValue = "memory")
    RunRepository noopRunRepository() {
        return RunRepository.noop();
    }

    /** 可恢复调度状态与幂等账本的存储端口；内存模式退化为不持久化。 */
    @Bean
    @ConditionalOnProperty(name = "pilot.store", havingValue = "sqlite", matchIfMissing = true)
    RunCheckpointRepository jdbcRunCheckpointRepository(SqliteStore store) {
        return new JdbcRunCheckpointRepository(store);
    }

    @Bean
    @ConditionalOnProperty(name = "pilot.store", havingValue = "memory")
    RunCheckpointRepository inMemoryRunCheckpointRepository() {
        return new InMemoryRunCheckpointRepository();
    }

    @Bean
    @ConditionalOnProperty(name = "pilot.store", havingValue = "sqlite", matchIfMissing = true)
    QueuedMessageRepository queuedMessageRepository(SqliteStore store) {
        return new QueuedMessageRepository(store);
    }

    @Bean
    @ConditionalOnProperty(name = "pilot.store", havingValue = "memory")
    QueuedMessageRepository inMemoryQueuedMessageRepository() {
        return new QueuedMessageRepository();
    }

    @Bean
    @ConditionalOnProperty(name = "pilot.store", havingValue = "sqlite", matchIfMissing = true)
    SettingsStore jdbcSettingsStore(SqliteStore store) {
        return new JdbcSettingsStore(store);
    }

    @Bean
    @ConditionalOnProperty(name = "pilot.store", havingValue = "memory")
    SettingsStore inMemorySettingsStore() {
        return new InMemorySettingsStore();
    }
}
