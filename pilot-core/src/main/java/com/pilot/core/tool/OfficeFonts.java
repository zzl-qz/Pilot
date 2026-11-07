package com.pilot.core.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import com.pilot.core.workspace.PathValidator;

/**
 * PDF 字体能力检测：纯 ASCII 用 PDF 标准字体（三平台一致），含中文时必须找到并嵌入
 * 一份可用字体，找不到就明确报错并给出替代操作，绝不静默输出缺字的 PDF。
 */
final class OfficeFonts {

    /** TTC 集合需要保持打开直到文档保存完毕。 */
    record FontHandle(PDFont font, TrueTypeCollection collection, String source) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            if (collection != null) {
                collection.close();
            }
        }
    }

    private OfficeFonts() {
    }

    static boolean needsEmbeddedFont(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7F) {
                return true;
            }
        }
        return false;
    }

    /** 标准 14 字体，不需要本机任何字体文件；只能覆盖 ASCII。 */
    static FontHandle standard(PDDocument document) {
        return new FontHandle(new PDType1Font(Standard14Fonts.FontName.HELVETICA), null, "PDF 标准 Helvetica");
    }

    /** 能力探测而非硬编码平台分支：单文件字体优先，TTC 集合放后面。 */
    static List<Path> systemCandidates() {
        List<Path> candidates = new ArrayList<>();
        String winDir = System.getenv("SystemRoot");
        if (winDir == null || winDir.isBlank()) {
            winDir = System.getenv("WINDIR");
        }
        if (winDir != null && !winDir.isBlank()) {
            Path fonts = Path.of(winDir, "Fonts");
            for (String name : List.of("simhei.ttf", "Deng.ttf", "simkai.ttf", "simfang.ttf", "msyh.ttc", "msyhbd.ttc",
                "simsun.ttc")) {
                candidates.add(fonts.resolve(name));
            }
        }
        for (String path : List.of("/Library/Fonts/Arial Unicode.ttf", "/System/Library/Fonts/PingFang.ttc",
            "/System/Library/Fonts/STHeiti Light.ttc", "/System/Library/Fonts/Supplemental/Songti.ttc",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/opentype/noto/NotoSerifCJK-Regular.ttc",
            "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc", "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
            "/usr/share/fonts/truetype/arphic/uming.ttc")) {
            candidates.add(Path.of(path));
        }
        return List.copyOf(candidates);
    }

    /** font_path 必须是工作区内的 .ttf/.otf/.ttc；否则给出所有实际存在的系统候选。 */
    static List<Path> candidates(PathValidator paths, String configured) {
        if (configured != null && !configured.isBlank()) {
            Path resolved = paths.resolve(configured.strip());
            String suffix = resolved.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!suffix.endsWith(".ttf") && !suffix.endsWith(".otf") && !suffix.endsWith(".ttc")) {
                throw new IllegalArgumentException("font_path 必须是工作区内的 .ttf/.otf/.ttc 字体文件");
            }
            if (!Files.isRegularFile(resolved)) {
                throw new IllegalArgumentException("font_path 指向的字体文件不存在：" + configured);
            }
            return List.of(resolved);
        }
        return systemCandidates().stream().filter(Files::isRegularFile).toList();
    }

    /** TTC 里逐个字体尝试，取第一个真正可编码的；单文件字体直接加载。 */
    static FontHandle load(PDDocument document, Path font) throws IOException {
        String suffix = font.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!suffix.endsWith(".ttc")) {
            return new FontHandle(PDType0Font.load(document, font.toFile()), null, font.toString());
        }
        TrueTypeCollection collection = new TrueTypeCollection(font.toFile());
        try {
            AtomicReference<PDType0Font> chosen = new AtomicReference<>();
            AtomicReference<IOException> last = new AtomicReference<>();
            collection.processAllFonts(ttf -> {
                if (chosen.get() == null) {
                    try {
                        chosen.set(PDType0Font.load(document, ttf, true));
                    } catch (IOException e) {
                        last.set(e);
                    }
                }
            });
            if (chosen.get() == null) {
                throw new IOException(
                    "字体集合中没有可加载的字体：" + font + (last.get() == null ? "" : "（" + last.get().getMessage() + "）"));
            }
            return new FontHandle(chosen.get(), collection, font.toString());
        } catch (IOException | RuntimeException e) {
            collection.close();
            throw e;
        }
    }

    static String missingFontHint() {
        return "PDF 含非 ASCII 字符，但本机未找到可嵌入的中文字体，也没有可用的 font_path。"
            + "可选替代：① 把一份开源中文字体（.ttf/.otf/.ttc）放进工作区并用 font_path 指定；"
            + "② 改用 create_docx 输出 Word 文档；③ 内容改为纯 ASCII 后重新生成。" + "不会生成缺字 PDF。";
    }
}
