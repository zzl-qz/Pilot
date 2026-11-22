package com.pilot.server.coordination;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.pilot.core.coordination.CoordinationScopeResolver;
import com.pilot.core.coordination.WorkspaceCoordinationEventBus;
import com.pilot.core.coordination.WorkspaceEventLimits;
import com.pilot.core.project.ProjectRepository;
import com.pilot.core.session.SessionRepository;
import com.pilot.core.session.SessionService;

/**
 * 工作区协调事件的装配：事件总线是显式注入的实例服务，进程内只有一个，随容器关闭释放订阅。
 *
 * <p>上限全部来自配置，默认值与其他事件流一样保守：历史、单条事件、订阅数与队列都有限。
 */
@Configuration
public class CoordinationEventsConfiguration {

    @Bean(destroyMethod = "close")
    public WorkspaceCoordinationEventBus workspaceCoordinationEvents(
        @Value("${pilot.workspace-events.max-events:2000}") int maxEvents,
        @Value("${pilot.workspace-events.max-bytes:4194304}") long maxBytes,
        @Value("${pilot.workspace-events.max-event-bytes:8192}") int maxEventBytes,
        @Value("${pilot.workspace-events.max-subscribers:256}") int maxSubscribers,
        @Value("${pilot.workspace-events.queue-items:512}") int queueItems,
        @Value("${pilot.workspace-events.replay-page-size:256}") int replayPageSize) {
        return new WorkspaceCoordinationEventBus(
            new WorkspaceEventLimits(maxEvents, maxBytes, maxEventBytes, maxSubscribers, queueItems, replayPageSize));
    }

    @Bean
    public CoordinationScopeResolver coordinationScopeResolver(ProjectRepository projects) {
        return new CoordinationScopeResolver(projects);
    }

    /**
     * 会话开停与会话内事件的工作区投影；构造时直接注册到会话服务，保证「建会话 / 归档会话」也能被感知。
     */
    @Bean
    public CoordinationEventRelay coordinationEventRelay(WorkspaceCoordinationEventBus events,
        SessionService sessions, SessionRepository repository) {
        CoordinationEventRelay relay = new CoordinationEventRelay(events, repository);
        sessions.setActivityListener(relay);
        return relay;
    }
}
