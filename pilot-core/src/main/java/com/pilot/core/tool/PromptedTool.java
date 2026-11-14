package com.pilot.core.tool;

import lombok.RequiredArgsConstructor;

/** 使用提示词资源描述、默认具有副作用的工具。 */
@RequiredArgsConstructor
public abstract class PromptedTool implements Tool {
    private final String name;

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean readOnly() {
        return false;
    }

}
