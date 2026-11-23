package com.pilot.server.workspace;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.core.util.HostOs;
import com.pilot.server.browser.BrowserRuntime;

/**
 * 内置跨平台通用文件预览引擎（零外部进程依赖，Windows / macOS / Linux 完全等价）。
 *
 * <p>基于纯 Java（Apache POI + JDK ZipFile + Spring 流式资源）覆盖图片、PDF、音视频、
 * Word(.docx)、Excel(.xlsx)、PowerPoint(.pptx) 与常见压缩包(.zip/.jar/.war/.epub)，
 * 并提供跨平台本机默认应用打开与文件夹定位能力。
 */
public final class UniversalFilePreviewService {
    static final long MAX_OFFICE_BYTES = 30L * 1024 * 1024;
    static final long MAX_ARCHIVE_BYTES = 200L * 1024 * 1024;
    static final long MAX_RAW_BYTES = 200L * 1024 * 1024;
    private static final int MAX_DOCX_BLOCKS = 600;
    private static final int MAX_SHEETS = 20;
    private static final int MAX_SHEET_ROWS = 300;
    private static final int MAX_SHEET_COLS = 40;
    private static final int MAX_SLIDES = 120;
    private static final int MAX_ARCHIVE_ENTRIES = 500;

    private UniversalFilePreviewService() {
    }

    public static PreviewMeta classify(Path file) {
        String ext = extension(file);
        return switch (ext) {
            case "png" -> new PreviewMeta("image", "image/png");
            case "jpg", "jpeg" -> new PreviewMeta("image", "image/jpeg");
            case "gif" -> new PreviewMeta("image", "image/gif");
            case "webp" -> new PreviewMeta("image", "image/webp");
            case "bmp" -> new PreviewMeta("image", "image/bmp");
            case "ico" -> new PreviewMeta("image", "image/x-icon");
            case "avif" -> new PreviewMeta("image", "image/avif");
            case "svg" -> new PreviewMeta("image", "image/svg+xml");
            case "pdf" -> new PreviewMeta("pdf", "application/pdf");
            case "mp3" -> new PreviewMeta("audio", "audio/mpeg");
            case "wav" -> new PreviewMeta("audio", "audio/wav");
            case "ogg" -> new PreviewMeta("audio", "audio/ogg");
            case "m4a" -> new PreviewMeta("audio", "audio/mp4");
            case "flac" -> new PreviewMeta("audio", "audio/flac");
            case "aac" -> new PreviewMeta("audio", "audio/aac");
            case "mp4", "m4v" -> new PreviewMeta("video", "video/mp4");
            case "webm" -> new PreviewMeta("video", "video/webm");
            case "mov" -> new PreviewMeta("video", "video/quicktime");
            case "ogv" -> new PreviewMeta("video", "video/ogg");
            case "docx" ->
                new PreviewMeta("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            case "xlsx" -> new PreviewMeta("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            case "pptx" ->
                new PreviewMeta("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
            case "zip", "jar", "war", "epub" -> new PreviewMeta("archive", "application/zip");
            default -> new PreviewMeta("binary", "application/octet-stream");
        };
    }

    public static boolean isRichPreviewKind(String kind) {
        return "image".equals(kind) || "pdf".equals(kind) || "audio".equals(kind) || "video".equals(kind)
            || "docx".equals(kind) || "xlsx".equals(kind) || "pptx".equals(kind) || "archive".equals(kind);
    }

    public static StructuredPreview parseStructured(Path file, String kind, long size) {
        try {
            return switch (kind) {
                case "docx" -> parseDocx(file, size);
                case "xlsx" -> parseXlsx(file, size);
                case "pptx" -> parsePptx(file, size);
                case "archive" -> parseArchive(file, size);
                default -> null;
            };
        } catch (Exception e) {
            return StructuredPreview.error(kind,
                "解析文件内容失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    public static ResponseEntity<Resource> rawResponse(Path file, boolean download) {
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不是文件: " + file.getFileName());
        }
        try {
            long size = Files.size(file);
            if (!download && size > MAX_RAW_BYTES) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "文件超过 200 MB，请改用下载或系统默认应用打开");
            }
            PreviewMeta meta = classify(file);
            String contentType = download ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : ("binary".equals(meta.kind()) ? probeContentType(file) : meta.mimeType());
            ResponseEntity.BodyBuilder builder = ResponseEntity.ok().contentType(MediaType.parseMediaType(contentType))
                .header("X-Content-Type-Options", "nosniff");
            if ("image/svg+xml".equals(contentType) || contentType.startsWith("text/html")) {
                builder.header("Content-Security-Policy",
                    "default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'; sandbox");
            }
            if (download) {
                builder.header(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename(file.getFileName().toString()).build().toString());
            }
            return builder.body(new FileSystemResource(file));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "无法读取文件", e);
        }
    }

    public static OpenResult openNative(Path file, String mode) {
        boolean reveal = "reveal".equalsIgnoreCase(mode);
        if (!Files.exists(file)) {
            return new OpenResult(false, "文件不存在：" + file.getFileName(), "请先刷新文件树。");
        }
        if (!BrowserRuntime.desktopAvailable()) {
            return new OpenResult(false, "当前服务端运行在无图形桌面环境，无法直接拉起本机应用。", "请点击「下载」将文件保存到本地电脑后再打开。");
        }
        if (!reveal && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            try {
                Desktop.getDesktop().open(file.toFile());
                return new OpenResult(true, "已在系统默认应用中打开 " + file.getFileName(), "");
            } catch (IOException | UnsupportedOperationException ignored) {
                // 回落至系统命令
            }
        }
        List<String> command = nativeCommand(file, reveal, HostOs.name());
        if (command.isEmpty()) {
            return new OpenResult(false, "当前操作系统暂不支持该原生打开操作。", "请点击「下载」后手动打开。");
        }
        try {
            new ProcessBuilder(command).redirectErrorStream(true).start();
            String action = reveal ? "已在系统文件夹中定位 " : "已在系统默认应用中打开 ";
            return new OpenResult(true, action + file.getFileName(), "");
        } catch (IOException e) {
            return new OpenResult(false, "调用系统应用失败：" + e.getMessage(), "请点击「下载」保存到本机后打开。");
        }
    }

    static List<String> nativeCommand(Path file, boolean reveal, String osName) {
        Path normalized = file.toAbsolutePath().normalize();
        Path parent = normalized.getParent() == null ? normalized : normalized.getParent();
        if (HostOs.isMac(osName)) {
            return reveal ? List.of("open", "-R", normalized.toString()) : List.of("open", normalized.toString());
        }
        if (HostOs.isWindows(osName)) {
            return reveal ? List.of("explorer.exe", "/select," + normalized)
                : List.of("cmd.exe", "/c", "start", "", normalized.toString());
        }
        return List.of("xdg-open", reveal ? parent.toString() : normalized.toString());
    }

    private static StructuredPreview parseDocx(Path file, long size) throws IOException {
        if (size > MAX_OFFICE_BYTES) {
            return StructuredPreview.error("docx", "Word 文档超过 30 MB，请使用「系统打开」或「下载」查看。");
        }
        List<DocBlock> blocks = new ArrayList<>();
        boolean truncated = false;
        try (InputStream in = Files.newInputStream(file); XWPFDocument doc = new XWPFDocument(in)) {
            for (IBodyElement element : doc.getBodyElements()) {
                if (blocks.size() >= MAX_DOCX_BLOCKS) {
                    truncated = true;
                    break;
                }
                if (element instanceof XWPFParagraph paragraph) {
                    String text = paragraph.getText() == null ? "" : paragraph.getText().strip();
                    if (!text.isEmpty()) {
                        blocks.add(new DocBlock("paragraph", headingLevel(paragraph.getStyle()), text, null));
                    }
                } else if (element instanceof XWPFTable table) {
                    List<List<String>> rows = new ArrayList<>();
                    for (XWPFTableRow row : table.getRows()) {
                        List<String> cells = new ArrayList<>();
                        for (XWPFTableCell cell : row.getTableCells()) {
                            cells.add(cell.getText() == null ? "" : cell.getText().strip());
                        }
                        rows.add(cells);
                    }
                    if (!rows.isEmpty()) {
                        blocks.add(new DocBlock("table", 0, "", rows));
                    }
                }
            }
        }
        return new StructuredPreview("docx", truncated, null, blocks, null, null, null);
    }

    private static int headingLevel(String style) {
        if (style == null || style.isBlank()) {
            return 0;
        }
        String lower = style.toLowerCase(Locale.ROOT);
        if (lower.startsWith("heading") || lower.startsWith("标题")) {
            for (int i = 0; i < lower.length(); i++) {
                char c = lower.charAt(i);
                if (c >= '1' && c <= '6') {
                    return c - '0';
                }
            }
            return 2;
        }
        return "title".equals(lower) ? 1 : 0;
    }

    private static StructuredPreview parseXlsx(Path file, long size) throws IOException {
        if (size > MAX_OFFICE_BYTES) {
            return StructuredPreview.error("xlsx", "Excel 表格超过 30 MB，请使用「系统打开」或「下载」查看。");
        }
        List<SheetData> sheets = new ArrayList<>();
        boolean truncated = false;
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        try (InputStream in = Files.newInputStream(file); Workbook workbook = WorkbookFactory.create(in)) {
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            int count = workbook.getNumberOfSheets();
            if (count > MAX_SHEETS) {
                truncated = true;
            }
            for (int s = 0; s < Math.min(count, MAX_SHEETS); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                SheetData parsed = readSheet(sheet, formatter, evaluator);
                if (parsed.truncated()) {
                    truncated = true;
                }
                sheets.add(parsed);
            }
        }
        return new StructuredPreview("xlsx", truncated, null, null, sheets, null, null);
    }

    private static SheetData readSheet(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        List<List<String>> rows = new ArrayList<>();
        int maxCol = 0;
        boolean sheetTruncated = false;
        int totalRows = 0;
        for (Row row : sheet) {
            totalRows++;
            if (rows.size() >= MAX_SHEET_ROWS) {
                sheetTruncated = true;
                continue;
            }
            int lastCell = Math.min(Math.max(0, (int)row.getLastCellNum()), MAX_SHEET_COLS);
            if (row.getLastCellNum() > MAX_SHEET_COLS) {
                sheetTruncated = true;
            }
            List<String> values = new ArrayList<>(lastCell);
            for (int c = 0; c < lastCell; c++) {
                Cell cell = row.getCell(c);
                values.add(cell == null ? "" : formatter.formatCellValue(cell, evaluator).strip());
            }
            maxCol = Math.max(maxCol, values.size());
            rows.add(values);
        }
        for (List<String> row : rows) {
            while (row.size() < maxCol) {
                row.add("");
            }
        }
        return new SheetData(sheet.getSheetName(), rows, totalRows, sheetTruncated);
    }

    private static StructuredPreview parsePptx(Path file, long size) throws IOException {
        if (size > MAX_OFFICE_BYTES) {
            return StructuredPreview.error("pptx", "PPT 演示文稿超过 30 MB，请使用「系统打开」或「下载」查看。");
        }
        List<SlideData> slides = new ArrayList<>();
        boolean truncated = false;
        try (InputStream in = Files.newInputStream(file); XMLSlideShow ppt = new XMLSlideShow(in)) {
            int index = 0;
            for (XSLFSlide slide : ppt.getSlides()) {
                index++;
                if (slides.size() >= MAX_SLIDES) {
                    truncated = true;
                    break;
                }
                slides.add(readSlide(slide, index));
            }
        }
        return new StructuredPreview("pptx", truncated, null, null, null, slides, null);
    }

    private static SlideData readSlide(XSLFSlide slide, int index) {
        String title = slide.getTitle() == null ? "" : slide.getTitle().strip();
        List<String> paragraphs = new ArrayList<>();
        List<List<List<String>>> tables = new ArrayList<>();
        for (XSLFShape shape : slide.getShapes()) {
            if (shape instanceof XSLFTextShape textShape) {
                for (XSLFTextParagraph p : textShape.getTextParagraphs()) {
                    String line = p.getText() == null ? "" : p.getText().strip();
                    if (!line.isEmpty() && !line.equals(title)) {
                        paragraphs.add(line);
                    }
                }
            } else if (shape instanceof XSLFTable table) {
                List<List<String>> rows = new ArrayList<>();
                for (XSLFTableRow row : table.getRows()) {
                    List<String> cells = new ArrayList<>();
                    for (XSLFTableCell cell : row.getCells()) {
                        cells.add(cell.getText() == null ? "" : cell.getText().strip());
                    }
                    rows.add(cells);
                }
                if (!rows.isEmpty()) {
                    tables.add(rows);
                }
            }
        }
        String notes = readSlideNotes(slide.getNotes());
        return new SlideData(index, title, paragraphs, tables, notes);
    }

    private static String readSlideNotes(XSLFNotes notes) {
        if (notes == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (XSLFShape shape : notes.getShapes()) {
            if (shape instanceof XSLFTextShape textShape) {
                String text = textShape.getText() == null ? "" : textShape.getText().strip();
                if (!text.isEmpty() && !text.matches("\\d+")) {
                    if (!out.isEmpty()) {
                        out.append('\n');
                    }
                    out.append(text);
                }
            }
        }
        return out.toString();
    }

    private static StructuredPreview parseArchive(Path file, long size) throws IOException {
        if (size > MAX_ARCHIVE_BYTES) {
            return StructuredPreview.error("archive", "压缩包超过 200 MB，请使用「系统打开」查看。");
        }
        List<ArchiveItem> items = new ArrayList<>();
        boolean truncated = false;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (items.size() >= MAX_ARCHIVE_ENTRIES) {
                    truncated = true;
                    break;
                }
                items.add(new ArchiveItem(entry.getName(), entry.isDirectory(), Math.max(0L, entry.getSize()),
                    Math.max(0L, entry.getCompressedSize())));
            }
        }
        return new StructuredPreview("archive", truncated, null, null, null, null, items);
    }

    private static String probeContentType(Path file) {
        try {
            String probed = Files.probeContentType(file);
            return probed == null || probed.isBlank() ? MediaType.APPLICATION_OCTET_STREAM_VALUE : probed;
        } catch (IOException e) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
    }

    private static String extension(Path file) {
        Path name = file.getFileName();
        if (name == null) {
            return "";
        }
        String text = name.toString();
        int dot = text.lastIndexOf('.');
        return dot < 0 ? "" : text.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public record PreviewMeta(String kind, String mimeType) {
    }

    public record OpenResult(boolean ok, String message, String alternative) {
    }

    public record DocBlock(String type, int level, String text, List<List<String>> rows) {
    }

    public record SheetData(String name, List<List<String>> rows, int totalRows, boolean truncated) {
    }

    public record SlideData(int index, String title, List<String> paragraphs, List<List<List<String>>> tables,
        String notes) {
    }

    public record ArchiveItem(String path, boolean directory, long size, long compressedSize) {
    }

    public record StructuredPreview(String kind, boolean truncated, String error, List<DocBlock> blocks,
        List<SheetData> sheets, List<SlideData> slides, List<ArchiveItem> entries) {
        static StructuredPreview error(String kind, String error) {
            return new StructuredPreview(kind, false, error, null, null, null, null);
        }
    }
}
