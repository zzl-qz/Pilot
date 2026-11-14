package com.pilot.core.model;

import java.util.Set;

import com.pilot.core.tool.ToolNames;

/** Maps local tool names to provider-managed capabilities hidden from the model's function-tool list. */
public final class NativeToolNames {
    private NativeToolNames() {
    }

    public static boolean isHidden(String name, Set<NativeCapability> capabilities) {
        if (name == null || capabilities == null) {
            return false;
        }
        return (ToolNames.WEB_SEARCH.equals(name) && capabilities.contains(NativeCapability.WEB_SEARCH))
            || (ToolNames.WEB_FETCH.equals(name) && capabilities.contains(NativeCapability.WEB_FETCH));
    }
}
