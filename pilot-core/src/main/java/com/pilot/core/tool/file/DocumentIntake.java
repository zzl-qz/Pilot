package com.pilot.core.tool.file;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.pilot.core.message.Attachment;
import com.pilot.core.tool.ToolResult;

/**
 * 用户直接丢进来的 PDF / Office。成熟产品会在这一轮就把正文交给模型，
 * 而不是只留一个路径等模型自己再读一遍。
 */
public final class DocumentIntake {
    public record Extract(String text, List<Attachment> images) {
    }

    private DocumentIntake() {
    }

    /** 抽不到或格式不对时返回 {@code null}，调用方保留原文件路径。 */
    public static Extract read(Path file) {
        if (file == null) {
            return null;
        }
        try {
            if (MediaFileReader.isPdf(file)) {
                ToolResult result = MediaFileReader.readPdf(file, file.getFileName().toString(), null);
                return new Extract(result.text(), result.images());
            }
            if (MediaFileReader.isOfficeText(file)) {
                ToolResult result = MediaFileReader.readOfficeText(file, file.getFileName().toString());
                if (result.text().startsWith("ERROR:")) {
                    return null;
                }
                return new Extract(result.text(), List.of());
            }
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
        return null;
    }
}
