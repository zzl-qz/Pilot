package com.pilot.core.tool.office;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
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

import com.pilot.core.util.FileNames;

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
    static void hardenZip() {
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
        return FileNames.dotExtension(file.getFileName().toString());
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
        try (InputStream in = Files.newInputStream(file); XSSFWorkbook book = new XSSFWorkbook(in)) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            for (Sheet sheet : book) {
                out.append("[sheet] ").append(sheet.getSheetName()).append('\n');
                for (Row row : sheet) {
                    List<String> values = new ArrayList<>();
                    for (Cell cell : row) {
                        values.add(formatter.formatCellValue(cell));
                    }
                    out.append(String.join(" | ", values)).append('\n');
                }
            }
        }
        return out.toString();
    }

    private static String presentationText(Path file) throws IOException {
        StringBuilder out = new StringBuilder();
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
        try (InputStream in = Files.newInputStream(file); XWPFDocument document = new XWPFDocument(in)) {
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                out.append(paragraph.getText()).append('\n');
            }
            for (XWPFTable table : document.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    List<String> cells = new ArrayList<>();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        cells.add(cell.getText());
                    }
                    out.append(String.join(" | ", cells)).append('\n');
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
