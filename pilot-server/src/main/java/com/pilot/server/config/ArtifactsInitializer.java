package com.pilot.server.config;

import jakarta.annotation.PostConstruct;

import org.springframework.stereotype.Component;

import com.pilot.core.tool.ToolArtifacts;
import com.pilot.server.common.data.PilotDataDir;

import lombok.RequiredArgsConstructor;

/**
 * 把工具产物（浏览器截图、PDF 渲染页、MCP 图片）的落盘目录指到数据目录下的 {@code artifacts/}，
 * 与会话数据放在一起，不污染用户的工作区。
 */
@Component
@RequiredArgsConstructor
public class ArtifactsInitializer {
    private final PilotDataDir dataDir;

    @PostConstruct
    void publish() {
        System.setProperty(ToolArtifacts.PROPERTY, dataDir.file("artifacts").toString());
    }
}
