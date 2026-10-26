package com.pilot.server.config;

import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelResponse;
import com.pilot.server.store.InMemorySettingsStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelClientProviderTest {

    @Test
    void rebuildsDelegateOnlyWhenSettingsChange() {
        var store = new InMemorySettingsStore();
        var settings = new ModelSettings(
                new ModelProperties("sk-1", "https://api.example.com/v1", "model-a",
                        List.of("model-a", "model-b")), store);
        var built = new ArrayList<ModelSettings.Snapshot>();
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
