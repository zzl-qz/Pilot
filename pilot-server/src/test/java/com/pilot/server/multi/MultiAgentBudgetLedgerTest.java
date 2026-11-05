package com.pilot.server.multi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.pilot.server.config.MultiAgentBudget;

class MultiAgentBudgetLedgerTest {
    @Test
    void reservesSharedBudgetAtomicallyAndInterruptsOtherWorker() throws Exception {
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch workerFinished = new CountDownLatch(1);
        AtomicReference<MultiAgentBudgetLedger.Settlement> settlement = new AtomicReference<>();

        try (MultiAgentBudgetLedger budget = new MultiAgentBudgetLedger(new MultiAgentBudget(600, 100), null)) {
            Thread worker = new Thread(() -> {
                try (MultiAgentBudgetLedger.WorkerLease ignored = budget.registerWorker()) {
                    MultiAgentBudgetLedger.RequestPermit permit = budget.beginRequest(60);
                    assertNotNull(permit);
                    requestStarted.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException e) {
                        interrupted.set(true);
                        settlement.set(permit.finish(20, 0, false));
                    } finally {
                        workerFinished.countDown();
                    }
                }
            });
            worker.start();

            assertTrue(requestStarted.await(2, TimeUnit.SECONDS));
            assertNull(budget.beginRequest(60), "两个并发预留之和超过上限时必须拒绝第二个请求");
            assertTrue(workerFinished.await(2, TimeUnit.SECONDS), "已准入请求应收到中断");
            assertTrue(interrupted.get());
            assertTrue(budget.snapshot().cancellationRequested());
            assertTrue(budget.snapshot().stopReason().contains("准入"));
            assertEquals(20, budget.snapshot().usedTokens());
            assertEquals(0, budget.snapshot().reservedTokens());
            worker.join(TimeUnit.SECONDS.toMillis(2));
        }
    }

    @Test
    void successfulResponseCanFinishButFurtherToolsAndRequestsAreStopped() {
        try (MultiAgentBudgetLedger budget = new MultiAgentBudgetLedger(new MultiAgentBudget(600, 100), null)) {
            MultiAgentBudgetLedger.RequestPermit permit = budget.beginRequest(20);
            assertNotNull(permit);

            MultiAgentBudgetLedger.Settlement settlement = permit.finish(120, 30, true);

            assertTrue(settlement.stoppedByThisRequest());
            assertFalse(settlement.stoppedBeforeSettle());
            assertTrue(budget.snapshot().stopReason().contains("达到上限"));
            assertEquals(120, budget.snapshot().usedTokens());
            assertEquals(30, budget.snapshot().estimatedTokens());
            assertFalse(budget.shouldCancelCurrentWorker(), "触顶响应允许完成当前模型步骤");
            assertTrue(budget.shouldCancelBeforeTool(), "预算停止后不再执行响应中的副作用工具");
            assertNull(budget.beginRequest(1), "后续模型请求必须被拒绝");
        }
    }

    @Test
    void cancelledDispatchIsReportedAsStoppedNotCompleted() {
        try (MultiAgentBudgetLedger budget = new MultiAgentBudgetLedger(new MultiAgentBudget(600, 0), null)) {
            budget.stopForCancellation();

            assertEquals("stopped", budget.snapshot().status());
            assertTrue(budget.snapshot().stopReason().contains("取消"));
            assertNull(budget.beginRequest(1), "取消后不再准入新请求");
            budget.complete();
            assertEquals("stopped", budget.snapshot().status(), "迟到的完成不能把取消覆盖成完成");
        }
    }
}
