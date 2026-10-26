package com.pilot.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 运行期数据的根目录：会话库、快照、trace、附件都放在这里。
 *
 * <p>默认是<strong>项目目录下的 {@code .pilot/}</strong>（{@code ${user.dir}/.pilot}），
 * 不再默认写入 C 盘用户目录；需要换位置时设置 {@code pilot.data.dir}
 * 或环境变量 {@code PILOT_DATA_DIR}。
 *
 * <p>{@code .pilot/} 已在 .gitignore 中，不会被提交。
 */
@Component
public class PilotDataDir {
    private final Path root;

    public PilotDataDir(@Value("${pilot.data.dir:}") String configured) {
        Path base = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.dir", "."), ".pilot")
                : Path.of(configured);
        this.root = base.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    /** 拼出子路径，例如 {@code file("traces", sessionId + ".jsonl")}。 */
    public Path file(String... parts) {
        Path path = root;
        for (String part : parts) {
            path = path.resolve(part);
        }
        return path;
    }
}
