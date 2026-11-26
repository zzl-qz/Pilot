package com.pilot.server.notifications;

import java.time.Clock;

import com.pilot.server.results.ResultInboxService;
import com.pilot.server.store.AutomationStore;

import tools.jackson.databind.ObjectMapper;

/** 仅限跨进程崩溃恢复测试构造连接本机测试 HttpServer 的 DeliveryService，不放宽生产 SSRF 限制。 */
public final class DeliveryServiceTestSupport {
    private DeliveryServiceTestSupport() {
    }

    public static DeliveryService loopbackForTest(AutomationStore store, ObjectMapper mapper,
        ResultInboxService results, Clock clock) {
        return new DeliveryService(store, mapper, results, clock, DeliveryService.httpSender(null), null, true);
    }
}
