package com.pilot.core.tool;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.pilot.core.loop.Attachment;
import com.pilot.core.workspace.PathValidator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 内置 Office 路径：真实 OOXML/PDF、公式计算、读回检查和页面图片。无 shell/外部 Office 依赖。 */
public final class OfficeTool implements Tool {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> ACTIONS = List.of("create_xlsx", "create_pptx", "create_docx", "create_pdf",
        "edit_xlsx", "edit_docx", "edit_pptx", "inspect", "render_pptx", "render_pdf");
    private final PathValidator paths;
    private final SnapshotSink snapshots;

    public OfficeTool(PathValidator paths, SnapshotSink snapshots) {
        this.paths = paths;
        this.snapshots = snapshots;
    }

    @Override
    public String name() {
        return "office";
    }

    @Override
    public String description() {
        return promptDescription();
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public boolean readOnly(Map<String, Object> args) {
        return "inspect".equals(args.get("action"));
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties",
            Map.of("action", Map.of("type", "string", "enum", ACTIONS, "description", "生成、编辑、检查或渲染动作"), "path",
                Map.of("type", "string", "description", "工作区内的 xlsx/pptx/docx/pdf 路径；创建与编辑都不得覆盖已有文件"), "spec_path",
                Map.of("type", "string", "description", "写操作必需的 JSON 规格文件；格式见工具说明"), "source_path",
                Map.of("type", "string", "description", "edit_* 必需的原始文件，编辑结果另存到 path"), "pages",
                Map.of("type", "string", "description", "仅 render_pdf：页码或范围，例如 3 或 2-5，默认全部页")),
            "required", List.of("action", "path"), "additionalProperties", false);
    }

    @Override
    public String execute(Map<String, Object> args) {
        return executeResult(args).text();
    }

    @Override
    public ToolResult executeResult(Map<String, Object> args) {
        List<Path> temps = new ArrayList<>();
        try {
            OfficeSupport.hardenZip();
            String action = ToolSupport.stringArg(args, "action");
            if (action == null || !ACTIONS.contains(action)) {
                throw new IllegalArgumentException("未知 office action：" + action);
            }
            Path file = paths.resolve(ToolSupport.stringArg(args, "path"));
            if ("inspect".equals(action)) {
                return ToolResult.text(inspect(file));
            }
            if ("render_pdf".equals(action)) {
                return OfficePdf.render(file, args.get("pages"));
            }
            if ("render_pptx".equals(action)) {
                return renderPptx(file);
            }
            JsonNode spec = readSpec(args);
            List<Pending> pending = new ArrayList<>();
            if (action.startsWith("edit_")) {
                Path source = paths.resolve(ToolSupport.stringArg(args, "source_path"));
                OfficeSupport.bounded(source, OfficeSupport.MAX_OFFICE_BYTES);
                if (source.equals(file)) {
                    throw new IllegalArgumentException("编辑结果必须另存为新文件，不能覆盖 source_path");
                }
                switch (action) {
                    case "edit_xlsx" -> pending.add(new Pending(file, ".xlsx", t -> editWorkbook(source, t, spec)));
                    case "edit_docx" -> pending.add(new Pending(file, ".docx", t -> OfficeDocx.edit(source, t, spec)));
                    case "edit_pptx" -> pending.add(new Pending(file, ".pptx", t -> editPresentation(source, t, spec)));
                    default -> throw new IllegalArgumentException("未知编辑动作：" + action);
                }
            } else {
                switch (action) {
                    case "create_xlsx" -> pending.add(new Pending(file, ".xlsx", t -> workbook(spec, t)));
                    case "create_pptx" -> pending.add(new Pending(file, ".pptx", t -> presentation(spec, t)));
                    case "create_docx" -> {
                        pending.add(new Pending(file, ".docx", t -> OfficeDocx.create(t, spec)));
                        String pdfPath = OfficeSupport.text(spec, "pdf_path", 500, false);
                        if (!pdfPath.isBlank()) {
                            pending.add(
                                new Pending(paths.resolve(pdfPath), ".pdf", t -> OfficePdf.create(t, spec, paths)));
                        }
                    }
                    case "create_pdf" -> pending.add(new Pending(file, ".pdf", t -> OfficePdf.create(t, spec, paths)));
                    default -> throw new IllegalArgumentException("未知创建动作：" + action);
                }
            }
            return deliver(pending, temps);
        } catch (Exception e) {
            return ToolResult.text("ERROR: Office 操作失败：" + e.getMessage());
        } finally {
            for (Path temp : temps) {
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignored) {
                    // 临时文件清理失败不影响已交付结果
                }
            }
        }
    }

    private JsonNode readSpec(Map<String, Object> args) throws Exception {
        Path specFile = paths.resolve(ToolSupport.stringArg(args, "spec_path"));
        OfficeSupport.bounded(specFile, OfficeSupport.MAX_SPEC_BYTES);
        return JSON.readTree(Files.readString(specFile));
    }

    /** 先把所有产物生成并读回校验，全部通过后才依次落盘；任一环节失败不留半成品。 */
    private ToolResult deliver(List<Pending> pending, List<Path> temps) throws Exception {
        for (Pending item : pending) {
            if (!item.target.toString().toLowerCase(Locale.ROOT).endsWith(item.extension)) {
                throw new IllegalArgumentException("目标扩展名必须为 " + item.extension);
            }
            if (Files.exists(item.target)) {
                throw new IllegalArgumentException("目标已存在，请选择新文件名；不会覆盖原件：" + paths.display(item.target));
            }
        }
        for (Pending item : pending) {
            Files.createDirectories(item.target.getParent());
            item.temp = Files.createTempFile(item.target.getParent(), ".pilot-office-", item.extension);
            temps.add(item.temp);
            item.generator.write(item.temp);
            OfficeSupport.bounded(item.temp, OfficeSupport.MAX_OFFICE_BYTES);
            item.verification = inspect(item.temp);
        }
        List<String> changed = new ArrayList<>();
        List<String> checks = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        for (Pending item : pending) {
            snapshots.beforeWrite(item.target);
            Files.move(item.temp, item.target);
            snapshots.afterWrite(item.target);
            changed.add(paths.display(item.target));
            checks.add("office inspect " + paths.display(item.target) + " → exit 0");
            out.append("已生成并重新解析 ").append(paths.display(item.target)).append('\n').append(item.verification)
                .append('\n');
            if (".pptx".equals(item.extension)) {
                out.append("尚需 render_pptx 查看每页图片，结构通过不代表视觉验收。\n");
            }
            if (".pdf".equals(item.extension)) {
                out.append("尚需 render_pdf 查看每页图片，结构通过不代表视觉验收。\n");
            }
        }
        return new ToolResult(out.toString().strip(), changed, checks);
    }

    /** 包内可见：{@link DocumentRenderer} 让 HTTP API 与工具走同一套生成逻辑。 */
    static void workbook(JsonNode spec, Path target) throws Exception {
        JsonNode sheets = spec.path("sheets");
        OfficeSupport.requireArray(sheets, 1, 32, "sheets");
        int cells = 0;
        try (var book = new XSSFWorkbook()) {
            CellStyle numberStyle = numberStyle(book, OfficeSupport.text(spec, "number_format", 64, false));
            for (JsonNode item : sheets) {
                var sheet = book.createSheet(item.path("name").asText());
                JsonNode rows = item.path("rows");
                OfficeSupport.requireArray(rows, 1, 10000, "rows");
                boolean header = item.path("header").asBoolean(false);
                CellStyle headerStyle = header ? headerStyle(book) : null;
                int r = 0;
                for (JsonNode values : rows) {
                    OfficeSupport.requireArray(values, 0, 256, "row");
                    var row = sheet.createRow(r++);
                    int c = 0;
                    for (JsonNode value : values) {
                        if (++cells > 50000) {
                            throw new IllegalArgumentException("一次最多 50000 单元格");
                        }
                        var cell = row.createCell(c++);
                        setCell(cell, value);
                        if (r == 1 && headerStyle != null) {
                            cell.setCellStyle(headerStyle);
                        } else if (numberStyle != null && cell.getCellType() == CellType.NUMERIC) {
                            cell.setCellStyle(numberStyle);
                        }
                    }
                }
                sheet.createFreezePane(0, 1);
                for (int col = 0; col < rows.get(0).size(); col++) {
                    sheet.setColumnWidth(col, 22 * 256);
                }
                JsonNode merges = item.path("merges");
                if (merges.isArray()) {
                    OfficeSupport.requireArray(merges, 0, 32, "merges");
                    for (JsonNode merge : merges) {
                        if (!merge.isTextual()) {
                            throw new IllegalArgumentException("merges 项必须是 A1:B2 形式的区域");
                        }
                        sheet.addMergedRegion(CellRangeAddress.valueOf(merge.asText()));
                    }
                }
            }
            verifyFormulas(book);
            try (OutputStream out = Files.newOutputStream(target)) {
                book.write(out);
            }
        }
    }

    private static void editWorkbook(Path source, Path target, JsonNode spec) throws Exception {
        try (InputStream in = Files.newInputStream(source); var book = new XSSFWorkbook(in);
            OutputStream out = Files.newOutputStream(target)) {
            JsonNode cells = spec.path("cells");
            if (cells.isArray()) {
                OfficeSupport.requireArray(cells, 0, 5_000, "cells");
                for (JsonNode item : cells) {
                    Sheet sheet = book.getSheet(OfficeSupport.text(item, "sheet", 128, true));
                    if (sheet == null) {
                        throw new IllegalArgumentException("找不到工作表：" + item.path("sheet"));
                    }
                    int rowIndex = item.path("row").asInt(-1);
                    int colIndex = item.path("col").asInt(-1);
                    if (rowIndex < 0 || rowIndex > 10_000 || colIndex < 0 || colIndex > 256) {
                        throw new IllegalArgumentException("row/col 须从 0 开始且不超出行列上限");
                    }
                    Row row = sheet.getRow(rowIndex);
                    if (row == null) {
                        row = sheet.createRow(rowIndex);
                    }
                    Cell cell = row.getCell(colIndex);
                    if (cell == null) {
                        cell = row.createCell(colIndex);
                    }
                    setCell(cell, item.path("value"));
                }
            }
            JsonNode appendRows = spec.path("append_rows");
            if (appendRows.isArray()) {
                OfficeSupport.requireArray(appendRows, 0, 32, "append_rows");
                for (JsonNode group : appendRows) {
                    Sheet sheet = book.getSheet(OfficeSupport.text(group, "sheet", 128, true));
                    if (sheet == null) {
                        throw new IllegalArgumentException("找不到工作表：" + group.path("sheet"));
                    }
                    JsonNode rows = group.path("rows");
                    OfficeSupport.requireArray(rows, 1, 10_000, "append_rows.rows");
                    int next = sheet.getLastRowNum() + 1;
                    for (JsonNode values : rows) {
                        OfficeSupport.requireArray(values, 0, 256, "append_rows.row");
                        Row row = sheet.createRow(next++);
                        int col = 0;
                        for (JsonNode value : values) {
                            setCell(row.createCell(col++), value);
                        }
                    }
                }
            }
            if (!cells.isArray() && !appendRows.isArray()) {
                throw new IllegalArgumentException("edit_xlsx 规格需要 cells 或 append_rows");
            }
            verifyFormulas(book);
            book.write(out);
        }
    }

    private static void setCell(Cell cell, JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return;
        }
        if (value.isNumber()) {
            cell.setCellValue(value.asDouble());
        } else if (value.isBoolean()) {
            cell.setCellValue(value.asBoolean());
        } else if (value.isObject() && value.has("formula")) {
            String formula = value.path("formula").asText();
            // 不解析外部工作簿、网络或宏调用。普通文本以 = 开头也仍保存为文本。
            if (formula.length() > 4096 || formula.matches("(?is).*(\\[|https?:|file:|WEBSERVICE|HYPERLINK|DDE).*")) {
                throw new IllegalArgumentException("不支持外部引用或网络公式");
            }
            cell.setCellFormula(formula.startsWith("=") ? formula.substring(1) : formula);
        } else if (value.isTextual()) {
            if (value.asText().length() > 32767) {
                throw new IllegalArgumentException("单元格文本长度无效");
            }
            cell.setCellValue(value.asText());
        } else {
            throw new IllegalArgumentException("单元格格式无效");
        }
    }

    private static CellStyle headerStyle(XSSFWorkbook book) {
        CellStyle style = book.createCellStyle();
        Font font = book.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private static CellStyle numberStyle(XSSFWorkbook book, String format) {
        if (format.isBlank()) {
            return null;
        }
        CellStyle style = book.createCellStyle();
        style.setDataFormat(book.createDataFormat().getFormat(format));
        return style;
    }

    private static void verifyFormulas(XSSFWorkbook book) {
        var evaluator = book.getCreationHelper().createFormulaEvaluator();
        evaluator.evaluateAll();
        for (var sheet : book) {
            for (var row : sheet) {
                for (var cell : row) {
                    if (cell.getCellType() == CellType.FORMULA && cell.getCachedFormulaResultType() == CellType.ERROR) {
                        throw new IllegalArgumentException("公式错误：" + sheet.getSheetName() + "!" + cell.getAddress());
                    }
                }
            }
        }
    }

    /** 包内可见：{@link DocumentRenderer} 让 HTTP API 与工具走同一套生成逻辑。 */
    static void presentation(JsonNode spec, Path target) throws Exception {
        JsonNode slides = spec.path("slides");
        OfficeSupport.requireArray(slides, 1, 40, "slides");
        try (var deck = new XMLSlideShow()) {
            deck.setPageSize(new Dimension(960, 540));
            addSlides(deck, slides, spec.path("theme").asText("light"));
            try (OutputStream out = Files.newOutputStream(target)) {
                deck.write(out);
            }
        }
    }

    private static void editPresentation(Path source, Path target, JsonNode spec) throws Exception {
        OfficeSupport.bounded(source, OfficeSupport.MAX_OFFICE_BYTES);
        JsonNode slides = spec.path("append_slides");
        OfficeSupport.requireArray(slides, 1, 40, "append_slides");
        try (InputStream in = Files.newInputStream(source); var deck = new XMLSlideShow(in);
            OutputStream out = Files.newOutputStream(target)) {
            if (deck.getSlides().size() + slides.size() > 40) {
                throw new IllegalArgumentException("追加后超过 40 页上限");
            }
            addSlides(deck, slides, spec.path("theme").asText("light"));
            deck.write(out);
        }
    }

    private static void addSlides(XMLSlideShow deck, JsonNode slides, String theme) {
        boolean dark = "dark".equals(theme);
        Color foreground = dark ? new Color(240, 246, 252) : new Color(25, 40, 55);
        for (JsonNode item : slides) {
            String title = item.path("title").asText();
            if (title.isBlank() || title.length() > 45) {
                throw new IllegalArgumentException("每页标题须为 1–45 字符");
            }
            JsonNode bullets = item.path("bullets");
            OfficeSupport.requireArray(bullets, 0, 7, "bullets");
            var slide = deck.createSlide();
            if (dark) {
                slide.getBackground().setFillColor(new Color(30, 41, 59));
            }
            var heading = slide.createTextBox();
            heading.setAnchor(new Rectangle2D.Double(48, 32, 864, 70));
            var titleRun = heading.addNewTextParagraph().addNewTextRun();
            titleRun.setText(title);
            titleRun.setFontSize(28.0);
            titleRun.setBold(true);
            titleRun.setFontColor(foreground);
            var body = slide.createTextBox();
            body.setAnchor(new Rectangle2D.Double(48, 126, 864, 360));
            for (JsonNode bullet : bullets) {
                if (!bullet.isTextual() || bullet.asText().length() > 90) {
                    throw new IllegalArgumentException("每条正文最多 90 字符，请拆页");
                }
                var paragraph = body.addNewTextParagraph();
                paragraph.setSpaceAfter(12.0);
                var run = paragraph.addNewTextRun();
                run.setText(bullet.asText());
                run.setFontSize(20.0);
                run.setFontColor(foreground);
            }
            if (heading.getTextHeight() > 70 || body.getTextHeight() > 360) {
                throw new IllegalArgumentException("文字超出页面布局，请减少文本或拆页");
            }
        }
    }

    private String inspect(Path file) throws Exception {
        OfficeSupport.bounded(file, OfficeSupport.MAX_OFFICE_BYTES);
        String result = switch (OfficeSupport.extension(file)) {
            case ".xlsx" -> spreadsheetInspect(file);
            case ".pptx" -> presentationInspect(file);
            case ".docx" -> OfficeDocx.inspect(file);
            case ".pdf" -> OfficePdf.inspect(file);
            default -> throw new IllegalArgumentException("仅支持 xlsx/pptx/docx/pdf 的检查");
        };
        return result.length() > OfficeSupport.MAX_INSPECT_CHARS
            ? result.substring(0, OfficeSupport.MAX_INSPECT_CHARS) + "\n（展示已截断，不能当作完整 JSON）" : result;
    }

    private static String spreadsheetInspect(Path file) throws Exception {
        List<Object> items = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file); var book = new XSSFWorkbook(in)) {
            if (!book.getExternalLinksTable().isEmpty()) {
                throw new IllegalArgumentException("工作簿含外部引用，不自动计算");
            }
            var formatter = new DataFormatter(Locale.ROOT);
            var evaluator = book.getCreationHelper().createFormulaEvaluator();
            int count = 0;
            for (var sheet : book) {
                List<Object> values = new ArrayList<>();
                for (var row : sheet) {
                    for (var cell : row) {
                        if (++count > 50000) {
                            throw new IllegalArgumentException("超过 50000 单元格检查上限");
                        }
                        var computed = evaluator.evaluate(cell);
                        if (computed != null && computed.getCellType() == CellType.ERROR) {
                            throw new IllegalArgumentException("单元格计算错误：" + cell.getAddress());
                        }
                        if (values.size() < 200) {
                            values.add(Map.of("cell", cell.getAddress().formatAsString(), "value",
                                formatter.formatCellValue(cell, evaluator), "formula",
                                cell.getCellType() == CellType.FORMULA ? cell.getCellFormula() : ""));
                        }
                    }
                }
                items.add(
                    Map.of("name", sheet.getSheetName(), "rows", sheet.getLastRowNum() + 1, "sample_cells", values));
            }
        }
        return JSON.writeValueAsString(Map.of("parsed", true, "format", "xlsx", "items", items));
    }

    private static String presentationInspect(Path file) throws Exception {
        List<Object> items = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file); var deck = new XMLSlideShow(in)) {
            if (deck.getSlides().size() > 40) {
                throw new IllegalArgumentException("超过 40 页检查上限");
            }
            for (var slide : deck.getSlides()) {
                String text =
                    slide.getShapes().stream().filter(XSLFTextShape.class::isInstance).map(XSLFTextShape.class::cast)
                        .map(XSLFTextShape::getText).collect(java.util.stream.Collectors.joining("\n"));
                items.add(Map.of("slide", slide.getSlideNumber(), "text", text));
            }
        }
        return JSON.writeValueAsString(Map.of("parsed", true, "format", "pptx", "items", items));
    }

    private ToolResult renderPptx(Path file) throws Exception {
        OfficeSupport.bounded(file, OfficeSupport.MAX_OFFICE_BYTES);
        OfficeSupport.ensureCjkFontRegistered();
        if (!".pptx".equals(OfficeSupport.extension(file))) {
            throw new IllegalArgumentException("需要 pptx 文件");
        }
        Path directory = paths.resolve(paths.display(file) + ".preview-" + java.util.UUID.randomUUID());
        List<Attachment> images = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file); var deck = new XMLSlideShow(in)) {
            if (deck.getSlides().isEmpty() || deck.getSlides().size() > 40) {
                throw new IllegalArgumentException("页数必须为 1–40");
            }
            Dimension size = deck.getPageSize();
            if (size.width < 1 || size.height < 1 || size.width > 2000 || size.height > 2000) {
                throw new IllegalArgumentException("页面尺寸超过渲染上限");
            }
            Files.createDirectory(directory);
            for (var slide : deck.getSlides()) {
                var image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics();
                try {
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, size.width, size.height);
                    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    slide.draw(graphics);
                } finally {
                    graphics.dispose();
                }
                Path png = directory.resolve("slide-" + slide.getSlideNumber() + ".png");
                snapshots.beforeWrite(png);
                ImageIO.write(image, "png", png.toFile());
                changed.add(paths.display(png));
                snapshots.afterWrite(png);
                if (images.size() < 4) {
                    images.add(new Attachment(png.getFileName().toString(), "image/png", png.toString()));
                }
            }
        } catch (Exception failure) {
            return new ToolResult(
                "ERROR: 渲染未完成：" + failure.getMessage() + "；已生成 " + changed.size() + " 页，目录=" + paths.display(directory),
                changed, List.of(), images);
        }
        return new ToolResult(
            "已渲染 " + changed.size() + " 页，图片目录：" + paths.display(directory)
                + "。仅附前四页，其余请 read_file 逐页检查；字体依赖本机，须检查缺字、重叠和裁切。",
            changed, List.of("office render_pptx " + paths.display(file) + " → exit 0"), images);
    }

    @FunctionalInterface
    private interface Generator {
        void write(Path temp) throws Exception;
    }

    private static final class Pending {
        private final Path target;
        private final String extension;
        private final Generator generator;
        private Path temp;
        private String verification;

        private Pending(Path target, String extension, Generator generator) {
            this.target = target;
            this.extension = extension;
            this.generator = generator;
        }
    }
}
