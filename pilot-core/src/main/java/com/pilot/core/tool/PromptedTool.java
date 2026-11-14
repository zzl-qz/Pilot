package com.pilot.core.tool;

/** 使用提示词资源描述、默认具有副作用的工具。 */
public abstract class PromptedTool implements Tool {
    private final String name;

    protected PromptedTool(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean readOnly() {
        return false;
    }

}
