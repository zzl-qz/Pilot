package com.pilot.server.session;

import com.pilot.core.session.SessionService;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockRunSimulatorTest {

    @Test
    void cancelsActiveRunOnlyOnce() {
        var sessions = new SessionService(Clock.systemUTC());
        var session = sessions.createSession("取消测试");
        var registry = new RunRegistry();
        var simulator = new MockRunSimulator(sessions, registry, true, 60_000);
        try {
            simulator.start(session.id(), "run_1", "跑一个长任务");
            assertTrue(registry.activeRun(session.id()).isPresent());

            assertTrue(simulator.cancel("run_1"));
            assertFalse(simulator.cancel("run_1"), "同一个 run 只接受一次取消");
            assertTrue(registry.activeRun(session.id()).isEmpty(), "取消后不应再被判定为活跃");
        } finally {
            simulator.shutdown();
        }
    }

    @Test
    void cannotCancelUnknownRun() {
        var simulator = new MockRunSimulator(new SessionService(), new RunRegistry(), true, 60_000);
        try {
            assertFalse(simulator.cancel("run_unknown"));
        } finally {
            simulator.shutdown();
        }
    }
}
