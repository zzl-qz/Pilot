package com.pilot.core.tool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;

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

    /** 执行安装命令，将合并后的输出写入日志。 */
    public static int runLogged(ProcessBuilder builder, Path logFile, Duration timeout, Logger log) {
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        try {
            Process process = builder.start();
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                log.warn("命令超时：{}", String.join(" ", builder.command()));
                return -1;
            }
            return process.exitValue();
        } catch (IOException e) {
            log.warn("命令无法执行：{}（{}）", String.join(" ", builder.command()), e.getMessage());
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
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
