package com.pilot.core.loop;

import com.pilot.core.model.ModelClient;
import com.pilot.core.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;

public abstract class AgentLoop {
    protected final List<Message> history = new ArrayList<>();
    protected final ModelClient client;
    protected final ToolRegistry registry;

    protected AgentLoop(ModelClient client, ToolRegistry registry) {
        this.client = client;
        this.registry = registry;
    }

    public abstract String run(String userInput);
}
