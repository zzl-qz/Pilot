package com.pilot.server.maintenance;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class DataMaintenanceTest {
    @Test
    void queuedOperationsMustRecheckDegradationAfterAcquiringTheLock() throws Exception {
        for (boolean queuedExclusive : new boolean[] {false, true}) {
            var maintenance = new DataMaintenance();
            var result = new CompletableFuture<Throwable>();
            Thread waiter;
            try (var held = queuedExclusive ? maintenance.activity() : maintenance.exclusive(Duration.ofSeconds(2))) {
                waiter = Thread.ofPlatform().start(() -> {
                    try (var scope =
                        queuedExclusive ? maintenance.exclusive(Duration.ofSeconds(2)) : maintenance.activity()) {
                        result.complete(null);
                    } catch (Throwable failure) {
                        result.complete(failure);
                    }
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (waiter.getState() != Thread.State.WAITING && waiter.getState() != Thread.State.TIMED_WAITING
                    && System.nanoTime() < deadline) {
                    Thread.sleep(1);
                }
                assertTrue(
                    waiter.getState() == Thread.State.WAITING || waiter.getState() == Thread.State.TIMED_WAITING);
                maintenance.block("恢复冲突");
            }
            assertInstanceOf(DataMaintenance.Busy.class, result.get(3, TimeUnit.SECONDS));
            waiter.join(3000);
            assertNull(maintenance.tryActivity());
        }
    }
}
