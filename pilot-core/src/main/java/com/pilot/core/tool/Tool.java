package com.pilot.core.tool;

import java.util.Map;

public interface Tool {
    String name();
    String description();
    String execute(Map<String, Object> args);
}
