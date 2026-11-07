package com.pilot.core.tool;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import com.pilot.core.workspace.PathValidator;

import tools.jackson.databind.JsonNode;

/**
 * 产物渲染门面：模型通过 {@link OfficeTool} 调用，人通过 HTTP API 调用，
 * 两者落到同一批生成函数，避免两套实现漂移。所有方法都不依赖模型。
 */
public final class DocumentRenderer {

    public static final List<String> FORMATS = List.of("docx", "pdf", "xlsx", "pptx");

    private DocumentRenderer() {
    }

    /** 生成一份产物；返回字体验收等说明，供调用方写入验收报告。 */
    public static String render(Path target, String format, JsonNode spec, PathValidator paths) throws Exception {
        switch (format) {
            case "docx" -> {
                OfficeDocx.create(target, spec);
                return "docx 已生成并读回";
            }
            case "pdf" -> {
                return "pdf " + OfficePdf.create(target, spec, paths);
            }
            case "xlsx" -> {
                OfficeTool.workbook(spec, target);
                return "xlsx 已生成并计算公式";
            }
            case "pptx" -> {
                OfficeTool.presentation(spec, target);
                return "pptx 已生成并读回";
            }
            default -> throw new IllegalArgumentException("不支持的导出格式：" + format);
        }
    }

    /** 独立读回：四类文件统一抽成可核对的纯文本，不还原排版。 */
    public static String inspect(Path file) throws IOException {
        OfficeSupport.bounded(file, OfficeSupport.MAX_OFFICE_BYTES);
        if (!OfficeSupport.isSupported(file)) {
            throw new IllegalArgumentException("仅支持 xlsx/pptx/docx/pdf 的读回");
        }
        return OfficeSupport.extractText(file);
    }

    /**
     * PDF 页面级编辑：合并、保留/删除页、旋转、页码、ASCII 水印。
     * 不做正文文字替换——PDF 没有段落模型，改写应回到可编辑源重新导出。
     */
    public static String editPdf(Path source, Path target, JsonNode spec, PathValidator paths) throws IOException {
        return editPdf(source, target, spec, paths, null);
    }

    /** mergeBase 允许直接引用本工作台已导出的产物文件名，仍做路径边界校验。 */
    public static String editPdf(Path source, Path target, JsonNode spec, PathValidator paths, Path mergeBase)
        throws IOException {
        OfficeSupport.bounded(source, OfficeSupport.MAX_OFFICE_BYTES);
        List<String> applied = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(source.toFile())) {
            JsonNode merge = spec.path("merge");
            if (merge.isArray()) {
                OfficeSupport.requireArray(merge, 0, 8, "merge");
                for (JsonNode item : merge) {
                    if (!item.isTextual()) {
                        throw new IllegalArgumentException("merge 项必须是 PDF 文件名或工作区内路径");
                    }
                    String name = item.asText();
                    Path extra;
                    if (mergeBase != null && !name.contains("/") && !name.contains("\\")
                        && name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                        extra = mergeBase.resolve(name).normalize();
                        if (!extra.startsWith(mergeBase.normalize())) {
                            throw new IllegalArgumentException("merge 文件名非法：" + name);
                        }
                    } else {
                        extra = paths.resolve(name);
                    }
                    OfficeSupport.bounded(extra, OfficeSupport.MAX_OFFICE_BYTES);
                    try (PDDocument other = Loader.loadPDF(extra.toFile())) {
                        for (PDPage page : other.getPages()) {
                            document.importPage(page);
                        }
                    }
                    applied.add("合并 " + item.asText());
                }
            }
            int total = document.getNumberOfPages();
            if (total < 1 || total > 200) {
                throw new IllegalArgumentException("PDF 页数须为 1–200");
            }
            Set<Integer> keep = new HashSet<>();
            for (int page = 1; page <= total; page++) {
                keep.add(page);
            }
            JsonNode keepPages = spec.path("keep_pages");
            if (keepPages.isArray()) {
                keep.clear();
                for (JsonNode item : keepPages) {
                    int page = item.asInt();
                    if (page < 1 || page > total) {
                        throw new IllegalArgumentException("页码超出范围：" + page);
                    }
                    keep.add(page);
                }
                applied.add("保留指定页");
            }
            JsonNode dropPages = spec.path("drop_pages");
            if (dropPages.isArray()) {
                for (JsonNode item : dropPages) {
                    int page = item.asInt();
                    if (page < 1 || page > total) {
                        throw new IllegalArgumentException("页码超出范围：" + page);
                    }
                    keep.remove(page);
                }
                applied.add("删除指定页");
            }
            if (keep.isEmpty()) {
                throw new IllegalArgumentException("不能删除全部页面");
            }
            for (int page = total; page >= 1; page--) {
                if (!keep.contains(page)) {
                    document.removePage(page - 1);
                }
            }
            JsonNode rotate = spec.path("rotate");
            if (rotate.isObject()) {
                int degrees = rotate.path("degrees").asInt();
                if (degrees % 90 != 0 || Math.abs(degrees) > 270) {
                    throw new IllegalArgumentException("旋转角度须为 90 的倍数且不超过 270");
                }
                JsonNode pages = rotate.path("pages");
                OfficeSupport.requireArray(pages, 1, 200, "rotate.pages");
                for (JsonNode item : pages) {
                    int index = item.asInt() - 1;
                    if (index < 0 || index >= document.getNumberOfPages()) {
                        throw new IllegalArgumentException("旋转页码超出范围：" + item.asInt());
                    }
                    PDPage page = document.getPage(index);
                    page.setRotation((page.getRotation() + degrees + 360) % 360);
                }
                applied.add("旋转 " + pages.size() + " 页");
            }
            String watermark = OfficeSupport.text(spec, "watermark", 120, false);
            if (!watermark.isBlank() && OfficeFonts.needsEmbeddedFont(watermark)) {
                throw new IllegalArgumentException("水印只支持 ASCII 文本；中文水印请用 create_pdf 生成正式文档");
            }
            boolean pageNumbers = spec.path("page_numbers").asBoolean(false);
            if (!watermark.isBlank() || pageNumbers) {
                PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                int pages = document.getNumberOfPages();
                for (int i = 0; i < pages; i++) {
                    PDPage page = document.getPage(i);
                    try (PDPageContentStream stream =
                        new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true)) {
                        stream.beginText();
                        stream.setFont(font, 10f);
                        stream.setNonStrokingColor(0.6f, 0.6f, 0.6f);
                        if (!watermark.isBlank()) {
                            stream.newLineAtOffset(60f, page.getMediaBox().getHeight() / 2f);
                            stream.showText(watermark);
                            stream.endText();
                            stream.beginText();
                            stream.setFont(font, 10f);
                            stream.setNonStrokingColor(0.6f, 0.6f, 0.6f);
                        }
                        if (pageNumbers) {
                            stream.newLineAtOffset(60f, 24f);
                            stream.showText((i + 1) + " / " + pages);
                        }
                        stream.endText();
                    }
                }
                if (!watermark.isBlank()) {
                    applied.add("加水印");
                }
                if (pageNumbers) {
                    applied.add("加页码");
                }
            }
            try (OutputStream out = Files.newOutputStream(target)) {
                document.save(out);
            }
            return "共 " + document.getNumberOfPages() + " 页；操作：" + String.join("、", applied);
        }
    }

    /** 允许的产物扩展名，避免下载端点被用作任意文件读取。 */
    public static boolean isRenderable(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return FORMATS.stream().anyMatch(format -> lower.endsWith("." + format));
    }
}
