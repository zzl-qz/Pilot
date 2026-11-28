package com.pilot.core.tool.office;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import com.pilot.core.message.Attachment;
import com.pilot.core.tool.ToolArtifacts;
import com.pilot.core.util.FileNames;
import com.pilot.core.util.Sha256;

import tools.jackson.databind.JsonNode;

/** office 与 read_file 共用的限额、OOXML 防护和四类文件的文本抽取。 */
public final class OfficeSupport {

    static final long MAX_SPEC_BYTES = 2_000_000L;
    static final long MAX_OFFICE_BYTES = 20_000_000L;
    static final int MAX_TEXT_CHARS = 30_000;
    static final int MAX_INSPECT_CHARS = 24_000;
    static final int MAX_BLOCKS = 400;
    static final int MAX_TABLE_CELLS = 5_000;
    static final int MAX_TEXT_LENGTH = 20_000;

    private static volatile boolean zipHardened;
    private static volatile boolean fontRegistered;

    private OfficeSupport() {
    }

    /** OOXML 是 zip：显式给出膨胀比、单条目、条目数和文本上限，避免只靠文件大小挡 zip bomb。 */
    public static void hardenZip() {
        if (zipHardened) {
            return;
        }
        synchronized (OfficeSupport.class) {
            if (zipHardened) {
                return;
            }
            ZipSecureFile.setMinInflateRatio(Math.max(ZipSecureFile.getMinInflateRatio(), 0.01));
            ZipSecureFile.setMaxEntrySize(50L * 1024 * 1024);
            ZipSecureFile.setMaxFileCount(10_000L);
            ZipSecureFile.setMaxTextSize(10L * 1024 * 1024);
            zipHardened = true;
        }
    }

    public static float boundedPdfDpi(PDPage page, float requestedDpi) {
        PDRectangle mediaBox = page.getMediaBox();
        if (mediaBox == null) {
            return requestedDpi;
        }
        PDRectangle box = mediaBox;
        PDRectangle cropBox = page.getCropBox();
        if (cropBox != null && cropBox.getWidth() > 0 && cropBox.getHeight() > 0
            && (cropBox.getWidth() < mediaBox.getWidth() || cropBox.getHeight() < mediaBox.getHeight())) {
            box = cropBox;
        }
        double width = Math.max(1.0, box.getWidth());
        double height = Math.max(1.0, box.getHeight());
        int rotation = Math.floorMod(page.getRotation(), 360);
        if (rotation == 90 || rotation == 270) {
            double swap = width;
            width = height;
            height = swap;
        }
        double pixelsPerPoint = requestedDpi / 72.0;
        double scale = Math.min(1.0, Math.min(8192.0 / (width * pixelsPerPoint), 8192.0 / (height * pixelsPerPoint)));
        scale = Math.min(scale, Math.sqrt(32_000_000.0 / (width * height * pixelsPerPoint * pixelsPerPoint)));
        return (float)(requestedDpi * scale);
    }

    public static String safeCellText(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell.getCellType() != CellType.FORMULA) {
            return formatter.formatCellValue(cell).strip();
        }
        try {
            String value = formatter.formatCellValue(cell, evaluator).strip();
            return value.contains("~CIRCULAR~") ? "=" + cell.getCellFormula() : value;
        } catch (IllegalArgumentException failure) {
            if (failure.getMessage() != null && failure.getMessage().startsWith("单元格计算错误：")) {
                throw failure;
            }
            return "=" + cell.getCellFormula();
        } catch (RuntimeException | StackOverflowError failure) {
            return "=" + cell.getCellFormula();
        }
    }

    public static BufferedImage renderPdfPage(PDFRenderer renderer, PDPage page, int pageIndex, float requestedDpi)
        throws IOException {
        return renderer.renderImageWithDPI(pageIndex, boundedPdfDpi(page, requestedDpi));
    }

    public static BufferedImage renderPdfPage(PDFRenderer renderer, PDPage page, int pageIndex, float requestedDpi,
        ImageType imageType) throws IOException {
        return renderer.renderImageWithDPI(pageIndex, boundedPdfDpi(page, requestedDpi), imageType);
    }

    /** PDF 逐页渲染结果：输出目录、实际成功渲染的页数以及附带的图片附件。 */
    public record RenderedPdf(Path directory, int renderedPages, List<Attachment> images) {
    }

    /**
     * 基于绝对路径、文件大小、最后修改时间与总页数生成稳定的 PDF 渲染缓存键，
     * 避免同名覆盖复用旧图或高页数截断碰撞。
     */
    public static String pdfCacheKey(Path file, int pages) throws IOException {
        var sha = Sha256.newDigest();
        sha.update(file.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
        sha.update((byte)0);
        sha.update(Long.toString(Files.size(file)).getBytes(StandardCharsets.UTF_8));
        sha.update((byte)0);
        sha.update(Long.toString(Files.getLastModifiedTime(file).toMillis()).getBytes(StandardCharsets.UTF_8));
        sha.update((byte)0);
        sha.update(Integer.toString(pages).getBytes(StandardCharsets.UTF_8));
        return Sha256.finishHex(sha).substring(0, 32);
    }

    /**
     * 解析 PDF 页码或范围（如 {@code "3"}、{@code "2-5"}）。
     *
     * @param defaultPages 入参为空时的默认最大页数
     * @param maxPages     单次允许的最大页数
     * @param clampUpper   {@code true} 时超出上限或总页数自动裁剪；{@code false} 时直接抛异常拒绝
     */
    public static int[] parsePageRange(Object rawValue, int total, int defaultPages, int maxPages, boolean clampUpper) {
        if (rawValue == null || rawValue.toString().isBlank()) {
            return new int[] {1, Math.min(total, defaultPages)};
        }
        String text = rawValue.toString().strip();
        int start;
        int end;
        try {
            int dash = text.indexOf('-');
            if (dash < 0) {
                start = end = Integer.parseInt(text);
            } else {
                start = Integer.parseInt(text.substring(0, dash).strip());
                end = Integer.parseInt(text.substring(dash + 1).strip());
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("pages 必须是页码或范围，例如 \"3\" 或 \"2-5\"");
        }
        if (start < 1 || end < start || start > total) {
            throw new IllegalArgumentException("pages 超出范围: " + text);
        }
        if (clampUpper) {
            end = Math.min(end, Math.min(total, start + maxPages - 1));
        } else if (end > total || end - start + 1 > maxPages) {
            throw new IllegalArgumentException("pages 超出范围: " + text);
        }
        return new int[] {start, end};
    }

    public record PdfRenderOptions(String artifactPrefix, float dpi, long maxPngBytes, int maxImages,
        boolean stopAtMaxImages) {
    }

    /** 按闭区间逐页渲染 PDF 到产物目录（按内容摘要去重），并按上限返回图片附件。 */
    public static RenderedPdf renderPdfPages(PDDocument document, Path source, int[] range, PdfRenderOptions options)
        throws IOException {
        Path dir = ToolArtifacts.directory(options.artifactPrefix() + pdfCacheKey(source, document.getNumberOfPages()));
        PDFRenderer renderer = new PDFRenderer(document);
        List<Attachment> images = new ArrayList<>();
        int rendered = 0;
        for (int page = range[0]; page <= range[1]; page++) {
            if (options.stopAtMaxImages() && images.size() >= options.maxImages()) {
                break;
            }
            BufferedImage image = renderPdfPage(renderer, document.getPage(page - 1), page - 1, options.dpi());
            Path png = dir.resolve("page-" + page + ".png");
            if (!ImageIO.write(image, "png", png.toFile()) || Files.size(png) > options.maxPngBytes()) {
                Files.deleteIfExists(png);
                continue;
            }
            rendered++;
            if (images.size() < options.maxImages()) {
                images.add(new Attachment(source.getFileName() + " 第 " + page + " 页", "image/png", png.toString()));
            }
        }
        return new RenderedPdf(dir, rendered, List.copyOf(images));
    }

    static void bounded(Path file, long limit) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) > limit) {
            throw new IllegalArgumentException("文件不存在或超过大小限制 " + limit);
        }
    }

    static void requireArray(JsonNode node, int min, int max, String field) {
        if (!node.isArray() || node.size() < min || node.size() > max) {
            throw new IllegalArgumentException(field + " 数量须为 " + min + "–" + max);
        }
    }

    /** 规格里的字符串一律显式取，避免把对象/数组/超长文本塞进产物。 */
    static String text(JsonNode node, String field, int maxLength, boolean required) {
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            if (required) {
                throw new IllegalArgumentException("缺少字段 " + field);
            }
            return "";
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(field + " 必须是字符串");
        }
        String text = value.asText();
        if (text.length() > maxLength) {
            throw new IllegalArgumentException(field + " 超过 " + maxLength + " 字符上限");
        }
        return text;
    }

    static String extension(Path file) {
        return FileNames.dotExtension(file);
    }

    public static boolean isOoxml(Path file) {
        return switch (extension(file)) {
            case ".xlsx", ".pptx", ".docx" -> true;
            default -> false;
        };
    }

    static boolean isSupported(Path file) {
        return isOoxml(file) || ".pdf".equals(extension(file));
    }

    /** 独立读回：把四类二进制文件抽成可核对的纯文本，不做任何排版还原。 */
    public static String extractText(Path file) throws IOException {
        hardenZip();
        bounded(file, MAX_OFFICE_BYTES);
        String out = switch (extension(file)) {
            case ".xlsx" -> spreadsheetText(file);
            case ".pptx" -> presentationText(file);
            case ".docx" -> documentText(file);
            case ".pdf" -> pdfText(file);
            default -> throw new IllegalArgumentException("不支持的文本抽取格式：" + extension(file));
        };
        return out.length() > MAX_TEXT_CHARS ? out.substring(0, MAX_TEXT_CHARS) + "\n（文本已截断）" : out;
    }

    private static String spreadsheetText(Path file) throws IOException {
        StringBuilder out = new StringBuilder();
        hardenZip();
        try (InputStream in = Files.newInputStream(file); XSSFWorkbook book = new XSSFWorkbook(in)) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            var evaluator = book.getCreationHelper().createFormulaEvaluator();
            for (Sheet sheet : book) {
                out.append("[sheet] ").append(sheet.getSheetName()).append('\n');
                for (Row row : sheet) {
                    List<String> values = new ArrayList<>();
                    for (Cell cell : row) {
                        values.add(safeCellText(cell, formatter, evaluator));
                    }
                    out.append(String.join(" | ", values)).append('\n');
                }
            }
        }
        return out.toString();
    }

    private static String presentationText(Path file) throws IOException {
        StringBuilder out = new StringBuilder();
        hardenZip();
        try (InputStream in = Files.newInputStream(file); XMLSlideShow deck = new XMLSlideShow(in)) {
            for (var slide : deck.getSlides()) {
                out.append("[slide ").append(slide.getSlideNumber()).append("]\n");
                for (var shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        out.append(textShape.getText()).append('\n');
                    }
                }
            }
        }
        return out.toString();
    }

    private static String documentText(Path file) throws IOException {
        StringBuilder out = new StringBuilder();
        hardenZip();
        try (InputStream in = Files.newInputStream(file); XWPFDocument document = new XWPFDocument(in)) {
            for (var element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    out.append(paragraph.getText()).append('\n');
                } else if (element instanceof XWPFTable table) {
                    for (XWPFTableRow row : table.getRows()) {
                        List<String> cells = new ArrayList<>();
                        for (XWPFTableCell cell : row.getTableCells()) {
                            cells.add(cell.getText());
                        }
                        out.append(String.join(" | ", cells)).append('\n');
                    }
                }
            }
        }
        return out.toString();
    }

    private static String pdfText(Path file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            int pages = document.getNumberOfPages();
            if (pages == 0) {
                return "[pdf] 0 页\n";
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(10, pages));
            return "[pdf] 共 " + pages + " 页\n" + stripper.getText(document);
        }
    }

    /** AWT 渲染 PPT 时尽力注册一份系统中文 TrueType 字体；失败不影响原有回退与明确提示。 */
    static void ensureCjkFontRegistered() {
        if (fontRegistered) {
            return;
        }
        synchronized (OfficeSupport.class) {
            if (fontRegistered) {
                return;
            }
            fontRegistered = true;
            for (Path candidate : OfficeFonts.systemCandidates()) {
                if (!Files.isRegularFile(candidate) || candidate.toString().toLowerCase(Locale.ROOT).endsWith(".ttc")) {
                    continue;
                }
                try (InputStream in = Files.newInputStream(candidate)) {
                    Font font = Font.createFont(Font.TRUETYPE_FONT, in);
                    GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
                    return;
                } catch (Exception ignored) {
                    // 字体不可用是能力缺失，不是错误；渲染结果文本会提示模型核对缺字。
                }
            }
        }
    }
}
