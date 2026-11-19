package com.pilot.server.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.pilot.core.message.Message;
import com.pilot.core.model.MeteredModelClient;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelProfile;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.model.ToolDefinition;
import com.pilot.server.store.InMemorySettingsStore;

class ModelClientProviderTest {

    @Test
    void requestSnapshotKeepsItsModelWhileTheNextRequestUsesTheNewSelection() {
        ModelSettings settings = new ModelSettings(
            new ModelProperties("key", "https://example.test/v1", "model-a", List.of()), new InMemorySettingsStore());
        ModelClient client = new ModelClientProvider(settings,
            snapshot -> (messages, tools) -> new ModelResponse(snapshot.model(), List.of()));
        ModelClient inFlight = client.snapshot();
        settings.update(null, "model-b", null);
        assertEquals("model-a", inFlight.chat(List.of(), List.of()).content(), "已发出的请求保持原模型");
        assertEquals("model-b", client.chat(List.of(), List.of()).content(), "后续调用使用新选择");
    }

    @Test
    void runningTaskKeepsTheInFlightRequestAndSwitchesOnTheNextOne() throws Exception {
        ModelSettings settings = new ModelSettings(
            new ModelProperties("key", "https://example.test/v1", "model-a", List.of()), new InMemorySettingsStore());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ModelClient provider = new ModelClientProvider(settings, snapshot -> new ModelClient() {
            @Override
            public ModelProfile profile() {
                return ModelProfile.resolve(null, snapshot.model(), ModelProfile.AUTO, ModelProfile.AUTO, "", null,
                    null, null, null);
            }

            @Override
            public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
                if ("model-a".equals(snapshot.model())) {
                    started.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                String text = messages.stream().map(Message::content).reduce("", (left, right) -> left + "\n" + right);
                return new ModelResponse(snapshot.model() + text, List.of());
            }
        });
        MeteredModelClient metered = new MeteredModelClient(provider, "run", "main", List.of(), report -> {
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = executor.submit(() -> metered.chat(List.of(), List.of()).content());
            assertTrue(started.await(5, TimeUnit.SECONDS));
            settings.update(null, "model-b", null);
            release.countDown();
            assertEquals("model-a", pending.get(5, TimeUnit.SECONDS));
            assertTrue(metered.chat(List.of(), List.of()).content().contains("模型已切换为 model-b"));
        }
    }

    @Test
    void concurrentRefreshCannotValidateAnOldClientWithTheNewRevision() throws Exception {
        ModelSettings settings = new ModelSettings(
            new ModelProperties("key", "https://example.test/v1", "model-a", List.of()), new InMemorySettingsStore());
        AtomicReference<String> revision = new AtomicReference<>("before");
        AtomicBoolean holdNextRead = new AtomicBoolean();
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ModelClient client = new ModelClientProvider(settings,
            snapshot -> (messages, tools) -> new ModelResponse(snapshot.model(), List.of()), () -> {
                if (holdNextRead.compareAndSet(true, false)) {
                    reading.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
                return revision.get();
            });
        assertEquals("model-a", client.chat(List.of(), List.of()).content());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            holdNextRead.set(true);
            var pending = executor.submit(() -> client.chat(List.of(), List.of()).content());
            try {
                assertTrue(reading.await(5, TimeUnit.SECONDS));
                settings.update(null, "model-b", null);
                revision.set("after");
                assertEquals("model-b", client.chat(List.of(), List.of()).content());
            } finally {
                release.countDown();
            }
            assertEquals("model-b", pending.get(5, TimeUnit.SECONDS), "不能拿新版本号为旧客户端放行");
        }
    }

    @Test
    void rebuildsDelegateOnlyWhenSettingsChange() {
        InMemorySettingsStore store = new InMemorySettingsStore();
        ModelSettings settings = new ModelSettings(
            new ModelProperties("sk-1", "https://api.example.com/v1", "model-a", List.of("model-a", "model-b")), store);
        ArrayList<ModelSettings.Snapshot> built = new ArrayList<ModelSettings.Snapshot>();
        ModelClient client = new ModelClientProvider(settings, snapshot -> {
            built.add(snapshot);
            return (messages, tools) -> new ModelResponse(snapshot.model(), List.of());
        });

        assertEquals("model-a", client.chat(List.of(), List.of()).content());
        assertEquals("model-a", client.chat(List.of(), List.of()).content());
        assertEquals(1, built.size(), "配置没变时不应重建客户端");

        settings.update(null, "model-b", null);
        assertEquals("model-b", client.chat(List.of(), List.of()).content());
        assertEquals(2, built.size(), "改模型后应重建一次");

        settings.update(null, null, "sk-2");
        client.chat(List.of(), List.of());
        assertEquals(3, built.size(), "换 API Key 也要重建，否则旧 Key 会继续生效");
    }
}
