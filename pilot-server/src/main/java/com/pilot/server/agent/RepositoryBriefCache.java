package com.pilot.server.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.pilot.core.util.Sha256;

import lombok.extern.slf4j.Slf4j;

/**
 * 仓库快照的跨会话缓存：按工作区存放「指纹 + 快照正文」，指纹没变就不再重新生成。
 *
 * <p>快照本身仍要进每个新会话（模型没有跨会话记忆），省下的是重复的 git 调用、
 * 目录遍历与 lint 发现；指纹覆盖分支、未提交改动和顶层条目，这些一变就重新生成。
 * 缓存读不出来只当作未命中，不影响正常注入。
 */
@Slf4j
final class RepositoryBriefCache {
    private static final int KEY_LENGTH = 16;

    private final Path dir;

    RepositoryBriefCache(Path dir) {
        this.dir = dir;
    }

    /** 工作区绝对路径的短摘要，避免把长路径直接当文件名。 */
    static String key(Path workspace) {
        return Sha256.hex(workspace.toAbsolutePath().normalize().toString()).substring(0, KEY_LENGTH);
    }

    /** 指纹一致时返回上次的快照正文；文件缺失、损坏或不一致都返回 null。 */
    String cached(String key, String fingerprint) {
        if (dir == null) {
            return null;
        }
        Path file = file(key);
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            int firstLineEnd = content.indexOf('\n');
            if (firstLineEnd < 0 || !content.substring(0, firstLineEnd).equals(fingerprint)) {
                return null;
            }
            String text = content.substring(firstLineEnd + 1).strip();
            return text.isEmpty() ? null : text;
        } catch (IOException e) {
            return null;
        }
    }

    void store(String key, String fingerprint, String text) {
        if (dir == null) {
            return;
        }
        Path file = file(key);
        try {
            Files.createDirectories(dir);
            Path staging = Files.createTempFile(dir, key, ".tmp");
            Files.writeString(staging, fingerprint + "\n" + text, StandardCharsets.UTF_8);
            Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // 缓存写失败不影响本轮注入，下一轮会重新生成
            log.debug("仓库快照缓存写入失败", e);
        }
    }

    private Path file(String key) {
        return dir.resolve(key + ".txt");
    }
}
