package com.pilot.core.loop;

/**
 * 用户在消息里附带的文件。
 *
 * <p>文本类附件在接入层就被解码内联进 {@code content}，不再出现在这里；
 * 这里只保留需要模型客户端二次处理（读取并编码）的附件，例如图片。
 *
 * @param name     原始文件名，仅用于展示与错误提示
 * @param mimeType MIME 类型，决定模型侧按什么格式发送
 * @param path     服务端落盘的绝对路径
 */
public record Attachment(String name, String mimeType, String path) {
    public Attachment {
        name = name == null || name.isBlank() ? "attachment" : name.strip();
        mimeType = mimeType == null || mimeType.isBlank() ? "application/octet-stream" : mimeType.strip();
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("attachment path must not be blank");
        }
    }

    public boolean image() {
        return mimeType.startsWith("image/");
    }
}
