package com.pilot.core.tool.office;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.IntStream;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

/**
 * 文件工作台的逐页高清预览：与 {@link OfficeTool} 共用 POI / PDFBox 能力。
 * 按目标目录（已绑定文件 SHA-256）做并发互斥与持久化缓存，翻页直接命中磁盘，不再整份重复重绘；
 * DOCX 还原标题层级、列表缩进与原位带框表格，XLSX 渲染带表头、对齐与网格线的工作表视图。
 */
public final class FilePreviews {
    /** 单次预览最多生成 30 页，避免大文件拖垮会话。 */
    public static final int MAX_PAGES = 30;
    private static final String META_FILE = ".preview-meta";
    private static final float PDF_DPI = 144f;
    private static final double SLIDE_SCALE = 1.5;
    private static final int PAGE_WIDTH = 1240;
    private static final int PAGE_HEIGHT = 1680;
    private static final int MARGIN = 68;
    private static final int FOOTER_SPACE = 64;
    private static final Object[] LOCKS = IntStream.range(0, 32).mapToObj(i -> new Object()).toArray();
    private static final List<String> PREFERRED_FONTS = List.of("PingFang SC", "Hiragino Sans GB", "Microsoft YaHei",
        "Noto Sans CJK SC", "Source Han Sans SC", "WenQuanYi Micro Hei", "SansSerif", Font.DIALOG);

    private static final Color PAPER = Color.WHITE;
    private static final Color INK = new Color(0x111827);
    private static final Color INK_SECONDARY = new Color(0x4B5563);
    private static final Color MUTED = new Color(0x6B7280);
    private static final Color BORDER = new Color(0xD1D5DB);
    private static final Color RULE = new Color(0xE5E7EB);
    private static final Color HEADER_BG = new Color(0xF3F4F6);
    private static final Color SHEET_TAG_BG = new Color(0xEEF2F6);

    private static volatile String cachedFontFamily;

    private FilePreviews() {
    }

    public record Result(int pages, String notice) {
    }

    public static Result generate(Path source, Path directory) throws IOException {
        OfficeSupport.bounded(source, OfficeSupport.MAX_OFFICE_BYTES);
        Path normalizedDir = directory.toAbsolutePath().normalize();
        Object lock = LOCKS[(normalizedDir.hashCode() & Integer.MAX_VALUE) % LOCKS.length];
        synchronized (lock) {
            Result cached = readCached(normalizedDir);
            if (cached != null) {
                return cached;
            }
            Files.createDirectories(normalizedDir);
            Result fresh = switch (OfficeSupport.extension(source)) {
                case ".pdf" -> pdf(source, normalizedDir);
                case ".pptx" -> pptx(source, normalizedDir);
                case ".docx" -> docx(source, normalizedDir);
                case ".xlsx" -> xlsx(source, normalizedDir);
                default -> plainContent(source, normalizedDir);
            };
            writeCached(normalizedDir, fresh);
            return fresh;
        }
    }

    private static Result readCached(Path directory) {
        Path meta = directory.resolve(META_FILE);
        if (!Files.isRegularFile(meta)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(meta, StandardCharsets.UTF_8);
            if (lines.size() < 2) {
                return null;
            }
            int pages = Integer.parseInt(lines.get(0).strip());
            if (pages < 1 || !Files.isRegularFile(directory.resolve("page-1.png"))
                || !Files.isRegularFile(directory.resolve("page-" + pages + ".png"))) {
                return null;
            }
            String notice = String.join("\n", lines.subList(1, lines.size()));
            return new Result(pages, notice);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static void writeCached(Path directory, Result result) {
        if (result.pages() < 1) {
            return;
        }
        try {
            Path temp = directory.resolve(META_FILE + ".tmp");
            Files.writeString(temp, result.pages() + "\n" + result.notice(), StandardCharsets.UTF_8);
            Files.move(temp, directory.resolve(META_FILE), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    private static Result pdf(Path source, Path directory) throws IOException {
        try (PDDocument document = Loader.loadPDF(source.toFile())) {
            int rawPages = document.getNumberOfPages();
            int total = Math.min(rawPages, MAX_PAGES);
            PDFRenderer renderer = new PDFRenderer(document);
            renderer.setSubsamplingAllowed(true);
            for (int page = 1; page <= total; page++) {
                BufferedImage img = renderer.renderImageWithDPI(page - 1, PDF_DPI, ImageType.RGB);
                writeAtomic(img, directory.resolve("page-" + page + ".png"));
            }
            String notice = "按 PDF 原始版式高清渲染" + (rawPages > MAX_PAGES ? "，仅前 " + MAX_PAGES + " 页" : "");
            return new Result(total, notice);
        }
    }

    private static Result pptx(Path source, Path directory) throws IOException {
        OfficeSupport.ensureCjkFontRegistered();
        try (InputStream in = Files.newInputStream(source); XMLSlideShow deck = new XMLSlideShow(in)) {
            List<XSLFSlide> slides = deck.getSlides();
            int total = Math.min(slides.size(), MAX_PAGES);
            Dimension size = deck.getPageSize();
            if (size.width < 1 || size.height < 1 || size.width > 2400 || size.height > 2400) {
                throw new IllegalArgumentException("演示文稿页面尺寸超过渲染上限");
            }
            for (int i = 0; i < total; i++) {
                writeAtomic(renderSlide(slides.get(i), size, SLIDE_SCALE),
                    directory.resolve("page-" + (i + 1) + ".png"));
            }
            return new Result(total,
                "按幻灯片版式高清渲染；字体依赖本机环境" + (slides.size() > MAX_PAGES ? "，仅前 " + MAX_PAGES + " 页" : ""));
        }
    }

    static BufferedImage renderSlide(XSLFSlide slide, Dimension size) {
        return renderSlide(slide, size, 1.0);
    }

    private static BufferedImage renderSlide(XSLFSlide slide, Dimension size, double scale) {
        int outW = Math.max(1, (int)Math.round(size.width * scale));
        int outH = Math.max(1, (int)Math.round(size.height * scale));
        BufferedImage image = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            configureQuality(g);
            g.setColor(PAPER);
            g.fillRect(0, 0, outW, outH);
            if (scale != 1.0) {
                g.scale(scale, scale);
            }
            slide.draw(g);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** Word 结构化排版预览：按文档真实元素顺序绘制标题层级、段落、项目符号与边框表格。 */
    private static Result docx(Path source, Path directory) throws IOException {
        OfficeSupport.hardenZip();
        OfficeSupport.ensureCjkFontRegistered();
        String family = uiFontFamily();
        List<DocItem> items = new ArrayList<>();
        try (InputStream in = Files.newInputStream(source); XWPFDocument doc = new XWPFDocument(in)) {
            for (IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph p) {
                    if (p.isPageBreak()) {
                        items.add(new DocItem(ItemKind.PAGE_BREAK, "", false, 18, List.of()));
                    } else {
                        items.add(parseParagraph(p));
                    }
                } else if (el instanceof XWPFTable t) {
                    List<List<String>> rows = extractTableRows(t);
                    if (!rows.isEmpty()) {
                        items.add(new DocItem(ItemKind.TABLE, "", false, 16, rows));
                    }
                }
            }
        }
        if (items.isEmpty()) {
            items.add(new DocItem(ItemKind.PARAGRAPH, "（空白文档）", false, 18, List.of()));
        }
        int pages = renderDocItems(source.getFileName().toString(), family, items, directory);
        return new Result(pages, "结构化排版预览（含标题层级与表格）；完整原生版式可点「用本机应用打开」");
    }

    private static DocItem parseParagraph(XWPFParagraph p) {
        String text = p.getText() == null ? "" : p.getText().stripTrailing();
        String style = p.getStyle() == null ? "" : p.getStyle().toLowerCase(Locale.ROOT);
        boolean bold = style.contains("heading") || style.contains("title");
        int fontSize = style.contains("title") ? 30
            : style.contains("heading1") || "1".equals(style) ? 24 : style.contains("heading") ? 20 : 18;
        for (XWPFRun run : p.getRuns()) {
            if (run.isBold()) {
                bold = true;
            }
            int pt = run.getFontSizeAsDouble() != null ? (int)Math.round(run.getFontSizeAsDouble() * 1.45) : -1;
            if (pt >= 22) {
                fontSize = Math.max(fontSize, Math.min(32, pt));
            }
        }
        boolean bullet =
            p.getNumID() != null || text.stripLeading().startsWith("•") || text.stripLeading().startsWith("- ");
        ItemKind kind = fontSize >= 20 && bold ? ItemKind.HEADING : bullet ? ItemKind.BULLET : ItemKind.PARAGRAPH;
        return new DocItem(kind, text.strip(), bold, fontSize, List.of());
    }

    private static List<List<String>> extractTableRows(XWPFTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(cell.getText() == null ? "" : cell.getText().strip());
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
            if (rows.size() >= 120) {
                break;
            }
        }
        return rows;
    }

    private static int renderDocItems(String fileName, String family, List<DocItem> items, Path dir)
        throws IOException {
        List<BufferedImage> pages = new ArrayList<>();
        BufferedImage canvas = newPageCanvas();
        Graphics2D g = openPageGraphics(canvas);
        int contentWidth = PAGE_WIDTH - MARGIN * 2;
        int bottomLimit = PAGE_HEIGHT - MARGIN - FOOTER_SPACE;
        int y = MARGIN + 10;

        for (DocItem item : items) {
            if (pages.size() >= MAX_PAGES) {
                break;
            }
            if (item.kind() == ItemKind.PAGE_BREAK) {
                g.dispose();
                pages.add(canvas);
                if (pages.size() >= MAX_PAGES) {
                    canvas = null;
                    break;
                }
                canvas = newPageCanvas();
                g = openPageGraphics(canvas);
                y = MARGIN + 10;
                continue;
            }
            if (item.kind() == ItemKind.TABLE) {
                TablePageState state =
                    drawDocTable(g, canvas, pages, family, item.rows(), y, contentWidth, bottomLimit);
                canvas = state.canvas();
                g = state.graphics();
                y = state.y() + 18;
                if (canvas == null) {
                    break;
                }
                continue;
            }
            if (item.text().isEmpty()) {
                y += 14;
                continue;
            }
            Font font = new Font(family, item.bold() ? Font.BOLD : Font.PLAIN, item.fontSize());
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();
            int indent = item.kind() == ItemKind.BULLET ? 26 : 0;
            int lineH = Math.max(28, (int)Math.round(item.fontSize() * 1.62));
            if (item.kind() == ItemKind.HEADING && y > MARGIN + 20) {
                y += 14;
            }
            List<String> wrapped = wrapLine(item.text(), fm, contentWidth - indent);
            for (int idx = 0; idx < wrapped.size(); idx++) {
                if (y + lineH > bottomLimit) {
                    g.dispose();
                    pages.add(canvas);
                    if (pages.size() >= MAX_PAGES) {
                        canvas = null;
                        break;
                    }
                    canvas = newPageCanvas();
                    g = openPageGraphics(canvas);
                    g.setFont(font);
                    y = MARGIN + 10;
                }
                if (item.kind() == ItemKind.BULLET && idx == 0 && !wrapped.get(idx).startsWith("•")) {
                    g.setColor(INK_SECONDARY);
                    g.drawString("•", MARGIN + 6, y + fm.getAscent());
                }
                g.setColor(INK);
                g.drawString(wrapped.get(idx), MARGIN + indent, y + fm.getAscent());
                y += lineH;
            }
            if (canvas == null) {
                break;
            }
            y += item.kind() == ItemKind.HEADING ? 8 : 6;
        }
        if (canvas != null) {
            g.dispose();
            pages.add(canvas);
        }
        return flushPagesWithFooter(pages, fileName + " · 文档预览", family, dir);
    }

    private static TablePageState drawDocTable(Graphics2D g, BufferedImage canvas, List<BufferedImage> pages,
        String family, List<List<String>> rows, int startY, int contentWidth, int bottomLimit) {
        int cols = Math.min(8, rows.stream().mapToInt(List::size).max().orElse(1));
        int colW = contentWidth / cols;
        Font headerFont = new Font(family, Font.BOLD, 15);
        Font cellFont = new Font(family, Font.PLAIN, 15);
        int padX = 12;
        int padY = 9;
        int y = startY + 6;
        Graphics2D curG = g;
        BufferedImage curCanvas = canvas;

        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            boolean header = r == 0;
            curG.setFont(header ? headerFont : cellFont);
            FontMetrics fm = curG.getFontMetrics();
            List<List<String>> wrappedCells = new ArrayList<>(cols);
            int maxLines = 1;
            for (int c = 0; c < cols; c++) {
                String val = c < row.size() ? row.get(c) : "";
                List<String> lines = wrapLine(val, fm, Math.max(40, colW - padX * 2));
                if (lines.size() > 4) {
                    lines = new ArrayList<>(lines.subList(0, 4));
                    lines.set(3, clipText(lines.get(3) + "…", fm, colW - padX * 2));
                }
                wrappedCells.add(lines);
                maxLines = Math.max(maxLines, lines.size());
            }
            int lineH = 22;
            int rowH = padY * 2 + maxLines * lineH;
            if (y + rowH > bottomLimit && y > MARGIN + 30) {
                curG.dispose();
                pages.add(curCanvas);
                if (pages.size() >= MAX_PAGES) {
                    return new TablePageState(null, null, y);
                }
                curCanvas = newPageCanvas();
                curG = openPageGraphics(curCanvas);
                y = MARGIN + 10;
            }
            int tableW = colW * cols;
            if (header) {
                curG.setColor(HEADER_BG);
                curG.fillRect(MARGIN, y, tableW, rowH);
            }
            curG.setColor(header ? BORDER : RULE);
            curG.setStroke(new BasicStroke(1f));
            curG.drawRect(MARGIN, y, tableW, rowH);
            for (int c = 0; c < cols; c++) {
                int cellX = MARGIN + c * colW;
                if (c > 0) {
                    curG.setColor(RULE);
                    curG.drawLine(cellX, y, cellX, y + rowH);
                }
                curG.setFont(header ? headerFont : cellFont);
                curG.setColor(header ? INK : INK_SECONDARY);
                List<String> cellLines = wrappedCells.get(c);
                for (int li = 0; li < cellLines.size(); li++) {
                    String line = cellLines.get(li);
                    boolean rightAlign = !header && cellLines.size() == 1 && looksNumeric(line);
                    int textX = rightAlign ? cellX + colW - padX - fm.stringWidth(line) : cellX + padX;
                    curG.drawString(line, Math.max(cellX + 4, textX), y + padY + fm.getAscent() + li * lineH);
                }
            }
            y += rowH;
        }
        return new TablePageState(curCanvas, curG, y);
    }

    /** Excel 电子表格预览：按工作表绘制表头、自适应列宽、网格线与数值右对齐。 */
    private static Result xlsx(Path source, Path directory) throws IOException {
        OfficeSupport.hardenZip();
        OfficeSupport.ensureCjkFontRegistered();
        String family = uiFontFamily();
        List<BufferedImage> pages = new ArrayList<>();
        DataFormatter formatter = new DataFormatter(Locale.ROOT);

        try (InputStream in = Files.newInputStream(source); XSSFWorkbook book = new XSSFWorkbook(in)) {
            for (int s = 0; s < book.getNumberOfSheets() && pages.size() < MAX_PAGES; s++) {
                Sheet sheet = book.getSheetAt(s);
                renderSheetPages(sheet, formatter, family, pages);
            }
        }
        if (pages.isEmpty()) {
            BufferedImage empty = newPageCanvas();
            Graphics2D g = openPageGraphics(empty);
            g.setFont(new Font(family, Font.PLAIN, 18));
            g.setColor(MUTED);
            g.drawString("（工作簿为空）", MARGIN, MARGIN + 40);
            g.dispose();
            pages.add(empty);
        }
        int total = flushPagesWithFooter(pages, source.getFileName() + " · 表格预览", family, directory);
        return new Result(total, "表格网格预览（数值自动右对齐）；筛选、公式编辑与宽表滚动请点「用本机应用打开」");
    }

    private static void renderSheetPages(Sheet sheet, DataFormatter formatter, String family,
        List<BufferedImage> pages) {
        List<List<CellVal>> grid = new ArrayList<>();
        int maxCols = 0;
        int lastRow = Math.min(sheet.getLastRowNum(), 300);
        for (int r = sheet.getFirstRowNum(); r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null || r < 0) {
                continue;
            }
            int cols = Math.min(Math.max(0, row.getLastCellNum()), 10);
            if (cols == 0) {
                continue;
            }
            List<CellVal> values = new ArrayList<>(cols);
            boolean anyText = false;
            for (int c = 0; c < cols; c++) {
                Cell cell = row.getCell(c);
                String text = cell == null ? "" : formatter.formatCellValue(cell).strip();
                boolean numeric = (cell != null && (cell.getCellType() == CellType.NUMERIC
                    || cell.getCellType() == CellType.FORMULA && looksNumeric(text))) || looksNumeric(text);
                if (!text.isEmpty()) {
                    anyText = true;
                }
                values.add(new CellVal(text, numeric));
            }
            if (anyText) {
                grid.add(values);
                maxCols = Math.max(maxCols, cols);
            }
        }
        if (grid.isEmpty() || maxCols == 0) {
            return;
        }
        int contentWidth = PAGE_WIDTH - MARGIN * 2;
        int bottomLimit = PAGE_HEIGHT - MARGIN - FOOTER_SPACE;
        int[] colWidths = computeColumnWidths(grid, maxCols, contentWidth, family);
        Font sheetFont = new Font(family, Font.BOLD, 16);
        Font headFont = new Font(family, Font.BOLD, 14);
        Font cellFont = new Font(family, Font.PLAIN, 14);
        int rowH = 34;

        int cursor = 0;
        int subPage = 1;
        while (cursor < grid.size() && pages.size() < MAX_PAGES) {
            BufferedImage canvas = newPageCanvas();
            Graphics2D g = openPageGraphics(canvas);
            int y = MARGIN;
            // 工作表标签条
            String tag = "工作表：" + sheet.getSheetName() + (subPage > 1 ? "（续 " + subPage + "）" : "");
            g.setFont(sheetFont);
            FontMetrics tfm = g.getFontMetrics();
            int tagW = Math.min(contentWidth, tfm.stringWidth(tag) + 28);
            g.setColor(SHEET_TAG_BG);
            g.fillRoundRect(MARGIN, y, tagW, 34, 8, 8);
            g.setColor(INK);
            g.drawString(tag, MARGIN + 14, y + 23);
            y += 48;

            // 每页重复冻结首行表头
            if (cursor > 0) {
                drawSheetRow(g, grid.getFirst(), colWidths, MARGIN, y, rowH, true, headFont, cellFont);
                y += rowH;
            }
            while (cursor < grid.size() && y + rowH <= bottomLimit) {
                drawSheetRow(g, grid.get(cursor), colWidths, MARGIN, y, rowH, cursor == 0, headFont, cellFont);
                y += rowH;
                cursor++;
            }
            g.dispose();
            pages.add(canvas);
            subPage++;
        }
    }

    private static void drawSheetRow(Graphics2D g, List<CellVal> row, int[] colWidths, int startX, int y, int rowH,
        boolean header, Font headFont, Font cellFont) {
        int totalW = 0;
        for (int w : colWidths) {
            totalW += w;
        }
        if (header) {
            g.setColor(HEADER_BG);
            g.fillRect(startX, y, totalW, rowH);
        }
        g.setColor(header ? BORDER : RULE);
        g.drawRect(startX, y, totalW, rowH);
        g.setFont(header ? headFont : cellFont);
        FontMetrics fm = g.getFontMetrics();
        int x = startX;
        for (int c = 0; c < colWidths.length; c++) {
            int w = colWidths[c];
            if (c > 0) {
                g.setColor(RULE);
                g.drawLine(x, y, x, y + rowH);
            }
            CellVal cell = c < row.size() ? row.get(c) : new CellVal("", false);
            if (!cell.text().isEmpty()) {
                String clipped = clipText(cell.text(), fm, w - 20);
                boolean right = !header && cell.numeric();
                int tx = right ? x + w - 10 - fm.stringWidth(clipped) : x + 10;
                g.setColor(header ? INK : INK_SECONDARY);
                g.drawString(clipped, Math.max(x + 6, tx), y + (rowH - fm.getHeight()) / 2 + fm.getAscent());
            }
            x += w;
        }
    }

    private static int[] computeColumnWidths(List<List<CellVal>> grid, int cols, int totalWidth, String family) {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        FontMetrics fm = probe.createGraphics().getFontMetrics(new Font(family, Font.BOLD, 14));
        double[] weights = new double[cols];
        int sample = Math.min(grid.size(), 40);
        for (int c = 0; c < cols; c++) {
            int maxPx = 64;
            for (int r = 0; r < sample; r++) {
                List<CellVal> row = grid.get(r);
                if (c < row.size()) {
                    maxPx = Math.max(maxPx, Math.min(360, fm.stringWidth(row.get(c).text()) + 28));
                }
            }
            weights[c] = maxPx;
        }
        double sum = 0;
        for (double w : weights) {
            sum += w;
        }
        int[] widths = new int[cols];
        int assigned = 0;
        for (int c = 0; c < cols - 1; c++) {
            widths[c] = Math.max(68, (int)Math.round(totalWidth * (weights[c] / sum)));
            assigned += widths[c];
        }
        widths[cols - 1] = Math.max(68, totalWidth - assigned);
        return widths;
    }

    private static Result plainContent(Path source, Path directory) throws IOException {
        OfficeSupport.ensureCjkFontRegistered();
        String family = uiFontFamily();
        List<DocItem> items = new ArrayList<>();
        for (String p : OfficeSupport.extractText(source).split("\\R")) {
            items.add(new DocItem(ItemKind.PARAGRAPH, p.strip(), false, 18, List.of()));
        }
        int pages = renderDocItems(source.getFileName().toString(), family, items, directory);
        return new Result(pages, "这是内容预览，不还原原生版式；真实排版请点「用本机应用打开」");
    }

    private static int flushPagesWithFooter(List<BufferedImage> pages, String footerPrefix, String family, Path dir)
        throws IOException {
        int total = Math.min(MAX_PAGES, pages.size());
        Font footFont = new Font(family, Font.PLAIN, 13);
        for (int i = 0; i < total; i++) {
            BufferedImage img = pages.get(i);
            Graphics2D g = img.createGraphics();
            try {
                configureQuality(g);
                g.setColor(RULE);
                g.drawLine(MARGIN, PAGE_HEIGHT - MARGIN - 28, PAGE_WIDTH - MARGIN, PAGE_HEIGHT - MARGIN - 28);
                g.setFont(footFont);
                g.setColor(MUTED);
                g.drawString(footerPrefix, MARGIN, PAGE_HEIGHT - MARGIN - 6);
                String pageNo = "第 " + (i + 1) + " / " + total + " 页";
                int pw = g.getFontMetrics().stringWidth(pageNo);
                g.drawString(pageNo, PAGE_WIDTH - MARGIN - pw, PAGE_HEIGHT - MARGIN - 6);
            } finally {
                g.dispose();
            }
            writeAtomic(img, dir.resolve("page-" + (i + 1) + ".png"));
        }
        return total;
    }

    private static BufferedImage newPageCanvas() {
        return new BufferedImage(PAGE_WIDTH, PAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
    }

    private static Graphics2D openPageGraphics(BufferedImage canvas) {
        Graphics2D g = canvas.createGraphics();
        configureQuality(g);
        g.setColor(PAPER);
        g.fillRect(0, 0, PAGE_WIDTH, PAGE_HEIGHT);
        return g;
    }

    private static void configureQuality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    private static String uiFontFamily() {
        String current = cachedFontFamily;
        if (current != null) {
            return current;
        }
        try {
            Set<String> available =
                Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT));
            for (String candidate : PREFERRED_FONTS) {
                if (available.contains(candidate)) {
                    cachedFontFamily = candidate;
                    return candidate;
                }
            }
        } catch (RuntimeException ignored) {
        }
        cachedFontFamily = Font.DIALOG;
        return Font.DIALOG;
    }

    private static List<String> wrapLine(String text, FontMetrics fm, int maxWidth) {
        if (text == null || text.isEmpty()) {
            return List.of("");
        }
        List<String> out = new ArrayList<>();
        for (String para : text.split("\\R")) {
            if (para.isEmpty()) {
                out.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < para.length(); i++) {
                line.append(para.charAt(i));
                if (fm.stringWidth(line.toString()) > maxWidth && line.length() > 1) {
                    char overflow = line.charAt(line.length() - 1);
                    line.deleteCharAt(line.length() - 1);
                    out.add(line.toString());
                    line.setLength(0);
                    line.append(overflow);
                }
            }
            if (!line.isEmpty()) {
                out.add(line.toString());
            }
        }
        return out.isEmpty() ? List.of("") : out;
    }

    private static String clipText(String value, FontMetrics fm, int maxWidth) {
        if (fm.stringWidth(value) <= maxWidth || value.length() <= 2) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value);
        while (sb.length() > 1 && fm.stringWidth(sb + "…") > maxWidth) {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb + "…";
    }

    private static boolean looksNumeric(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.strip().matches("^[+-]?[$¥€£]?\\d[\\d,]*(?:\\.\\d+)?%?$");
    }

    private static void writeAtomic(BufferedImage image, Path target) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        if (!ImageIO.write(image, "png", temp.toFile())) {
            Files.deleteIfExists(temp);
            throw new IOException("无法写入预览图片：" + target.getFileName());
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private enum ItemKind {
        HEADING, PARAGRAPH, BULLET, TABLE, PAGE_BREAK
    }

    private record DocItem(ItemKind kind, String text, boolean bold, int fontSize, List<List<String>> rows) {
    }

    private record CellVal(String text, boolean numeric) {
    }

    private record TablePageState(BufferedImage canvas, Graphics2D graphics, int y) {
    }
}
