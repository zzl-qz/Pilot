package com.pilot.core.model;

import java.util.Set;

/** Maps local tool names to provider-managed capabilities hidden from the model's function-tool list. */
public final class NativeToolNames {
    private NativeToolNames() {
    }

    public static boolean isHidden(String name, Set<NativeCapability> capabilities) {
        if (name == null || capabilities == null) {
            return false;
        }
        return ("web_search".equals(name) && capabilities.contains(NativeCapability.WEB_SEARCH))
            || ("web_fetch".equals(name) && capabilities.contains(NativeCapability.WEB_FETCH));
    }
}
