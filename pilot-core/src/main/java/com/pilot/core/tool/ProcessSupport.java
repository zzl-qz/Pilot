package com.pilot.core.tool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 子进程执行的公共细节：跨平台 shell 包装与输出解码。
 *
 * <p>Windows 上子进程的 stdout/stderr 常用本地编码（GBK），直接按 UTF-8 解码会把中文变成替换字符，
 * 模型读到一堆乱码就无法判断构建是否真的通过；因此统一「严格 UTF-8，失败回退 GBK」。
 */
public final class ProcessSupport {
    /** Windows 中文环境的默认编码，作为非 UTF-8 输出的回退。 */
    private static final Charset GBK = Charset.forName("GBK");
    private static final boolean WINDOWS =
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private ProcessSupport() {
    }

    /** 用平台 shell 执行一条命令行：Windows 走 {@code cmd.exe /c}，其余走 {@code /bin/sh -c}。 */
    public static List<String> shell(String command) {
        return WINDOWS ? List.of("cmd.exe", "/c", command) : List.of("/bin/sh", "-c", command);
    }

    /** 优先按 UTF-8 严格解码；出现非法字节时回退 GBK。 */
    public static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, GBK);
        }
    }

    /** 读取进程输出并解码；读取失败返回空串，不影响主流程。 */
    public static String read(InputStream stream) {
        try (stream) {
            return decode(stream.readAllBytes());
        } catch (IOException e) {
            return "";
        }
    }
}
