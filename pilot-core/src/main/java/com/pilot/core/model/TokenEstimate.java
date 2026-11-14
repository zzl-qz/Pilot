package com.pilot.core.model;

import java.util.List;

import com.pilot.core.message.Message;

/**
 * 粗算文本大概占多少 token，给预算和压缩用，不是模型的真实计数。
 * ASCII 按 4 个字符 1 个 token，非 ASCII（中文等）按 1 个字符 1 个 token。
 * 纯按字符数除以 4 会把中文估少，压缩触发太晚，请求可能直接超出模型窗口。
 */
public final class TokenEstimate {
    private TokenEstimate() {
    }

    public static int of(List<Message> messages) {
        int total = 0;
        for (Message message : messages) {
            total += of(message.content());
        }
        return total;
    }

    public static int of(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        int ascii = 0;
        int wide = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) < 0x80) {
                ascii++;
            } else {
                wide++;
            }
        }
        return (ascii + 3) / 4 + wide;
    }
}
