package com.pilot.server.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.pilot.server.config.PilotDataDir;

/**
 * 访问审计：只记录拒绝、限流和令牌缺失，成功请求不逐条落盘。
 *
 * <p>成功的 GET 会产生大量噪声；真正需要复盘的是「谁在什么时候被拒绝、为什么」。
 * 日志不写令牌本身，只写令牌是否存在。
 */
@Component
public class AccessAudit {
    private static final Logger log = LoggerFactory.getLogger(AccessAudit.class);
    private final Path file;
    private final boolean enabled;

    public AccessAudit(PilotDataDir dataDir, SecurityProperties properties) {
        this.file = dataDir.file("audit", "access.jsonl");
        this.enabled = properties.audit();
    }

    public void record(String remote, String method, String path, int status, String reason, boolean tokenPresent) {
        if (!enabled) {
            return;
        }
        String line = String.format(
            "{\"at\":\"%s\",\"remote\":\"%s\",\"method\":\"%s\",\"path\":\"%s\","
                + "\"status\":%d,\"reason\":\"%s\",\"token\":%s}%n",
            Instant.now(), escape(remote), escape(method), escape(path), status, escape(reason),
            tokenPresent ? "true" : "false");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.debug("写入访问审计失败（不影响请求处理）", e);
        }
    }

    public Path file() {
        return file;
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
