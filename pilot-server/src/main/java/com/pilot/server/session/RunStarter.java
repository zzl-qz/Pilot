package com.pilot.server.session;

/**
 * 一次运行的启动器。控制器只依赖该接口，具体实现由 {@code pilot.agent.mode} 决定：
 * mock 走离线剧本，real 走 {@code AgentLoop}。
 */
public interface RunStarter {
    void start(String sessionId, String runId, String input);
}
