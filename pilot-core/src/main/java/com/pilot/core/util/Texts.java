package com.pilot.core.util;

/** 处理一段文本的小工具：压成一行、判断是不是二进制内容。 */
public final class Texts {
    /** 判断二进制时只看开头这么多字节。 */
    public static final int BINARY_SAMPLE_BYTES = 8000;

    private Texts() {
    }

    /**
     * 把一段文本压成一行：连续空白并成一个空格，去掉首尾空白，超长就截断并加省略号。
     * 用在日志、摘要、事件标题这些只能放一行的地方。
     */
    public static String oneLine(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String single = text.replaceAll("\\s+", " ").strip();
        return single.length() <= maxChars ? single : single.substring(0, maxChars) + "…";
    }

    /** 开头一段里有没有 NUL 字节，有就当二进制文件。只是粗略判断。 */
    public static boolean isBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, BINARY_SAMPLE_BYTES);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }
}
