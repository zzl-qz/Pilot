package com.pilot.core.tool;

public abstract class ToolRegistry {
    public abstract void register(Tool tool);
    public abstract Tool find(String name);
}
