package com.pilot.server.config;

import com.pilot.core.session.InMemorySessionRepository;
import com.pilot.core.session.RunRepository;
import com.pilot.core.session.SessionRepository;
import com.pilot.server.store.EventCodec;
import com.pilot.server.store.InMemorySettingsStore;
import com.pilot.server.store.JdbcRunRepository;
import com.pilot.server.store.JdbcSessionRepository;
import com.pilot.server.store.JdbcSettingsStore;
import com.pilot.server.store.SettingsStore;
import com.pilot.server.store.SqliteStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 存储装配：{@code pilot.store=sqlite}（默认，落盘到 ~/.pilot/pilot.db）或 {@code memory}（进程内）。
 * 测试使用 memory，避免污染本地数据库。
 */
@Configuration(proxyBeanMethods = false)
public class StoreConfiguration {
    private static final Logger log = LoggerFactory.getLogger(StoreConfiguration.class);

    @Bean
    @ConditionalOnProperty(name = "pilot.store", havingValue = "sqlite", matchIfMissing = true)
    SqliteStore sqliteStore(@Value("${pilot.store.path:}") String configured) {
        Path path = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home", "."), ".pilot", "pilot.db")
                : Path.of(configured);
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
    @ConditionalOnProperty(name = "pilot.store", havingValue = "memory")
    RunRepository noopRunRepository() {
        return RunRepository.noop();
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

    /** 进程重启后，库里遗留的 RUNNING 记录不可能再继续，统一标记为中断。 */
    @Bean
    ApplicationRunner interruptedRunMarker(RunRepository runs) {
        return args -> {
            int marked = runs.markInterrupted(Instant.now());
            if (marked > 0) {
                log.warn("检测到 {} 个上次未正常结束的运行，已标记为 INTERRUPTED", marked);
            }
        };
    }
}
