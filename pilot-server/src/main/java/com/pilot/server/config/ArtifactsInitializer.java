package com.pilot.server.config;

import jakarta.annotation.PostConstruct;

import org.springframework.stereotype.Component;

import com.pilot.core.tool.ToolArtifacts;

/**
 * 把工具产物（浏览器截图、PDF 渲染页、MCP 图片）的落盘目录指到数据目录下的 {@code artifacts/}，
 * 与会话数据放在一起，不污染用户的工作区。
 */
@Component
public class ArtifactsInitializer {
    private final PilotDataDir dataDir;

    public ArtifactsInitializer(PilotDataDir dataDir) {
        this.dataDir = dataDir;
    }

    @PostConstruct
    void publish() {
        System.setProperty(ToolArtifacts.PROPERTY, dataDir.file("artifacts").toString());
    }
}
