package com.pilot.server.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.pilot.core.model.diagnostics.ModelRequestDumper;
import com.pilot.server.store.PilotDataDir;

/**
 * 将 {@code pilot.debug.dump-model-requests}（默认关闭）同步到 {@link ModelRequestDumper}。
 */
@Component
public class ModelRequestDumpConfiguration {
    private final boolean enabled;

    public ModelRequestDumpConfiguration(@Value("${pilot.debug.dump-model-requests:false}") boolean enabled,
        PilotDataDir dataDir) {
        this.enabled = enabled;
        ModelRequestDumper.configure(enabled, dataDir.root());
    }

    public boolean isEnabled() {
        return enabled;
    }
}
