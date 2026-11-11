package com.pilot.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.pilot.core.event.AssistantPreview;

@EnabledIfSystemProperty(named = "pilot.events.benchmark", matches = "true")
class PreviewBenchmarkTest {
    @Test
    void compareMergeWindowsAndByteThresholds() throws Exception {
        var reports = new ArrayList<String>();
        for (int millis : new int[] {20, 50, 100}) {
            for (int bytes : new int[] {2048, 8192, 16384}) {
                var service = new SessionService(Clock.systemUTC(), new InMemorySessionRepository(),
                    EventStreamLimits.defaults(), new PreviewLimits(millis, bytes, 131072, 16777216, 256));
                String id = service.createSession("合并测量").id();
                String run = service.sendMessage(id, "开始").runId();
                var parts = new CopyOnWriteArrayList<String>();
                long began = System.nanoTime();
                try (var sub =
                    service.subscribe(id, 1, event -> parts.add(((AssistantPreview)event.payload()).text()))) {
                    for (int i = 0; i < 1000; i++) {
                        service.preview(id, run, new AssistantPreview("中🙂"));
                    }
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (parts.stream().mapToInt(String::length).sum() < 3000 && System.nanoTime() < deadline) {
                        Thread.sleep(1);
                    }
                    assertEquals("中🙂".repeat(1000), String.join("", parts));
                    assertTrue(parts.size() <= 100);
                    reports.add(
                        "{\"windowMs\":" + millis + ",\"thresholdBytes\":" + bytes + ",\"inputs\":1000,\"outputs\":"
                            + parts.size() + ",\"caughtUpMs\":" + (System.nanoTime() - began) / 1e6 + "}");
                } finally {
                    service.delete(id);
                }
            }
        }
        Files.createDirectories(Path.of("target/event-benchmarks"));
        Files.writeString(Path.of("target/event-benchmarks/preview.json"), "[" + String.join(",", reports) + "]");
    }
}
