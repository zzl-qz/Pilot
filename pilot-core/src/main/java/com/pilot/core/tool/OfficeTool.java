package com.pilot.core.tool;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.pilot.core.loop.Attachment;
import com.pilot.core.workspace.PathValidator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 内置 Office 路径：真实 OOXML、公式计算、读回检查和幻灯片图片。无 shell/外部 Office 依赖。 */
public final class OfficeTool implements Tool {
    private static final ObjectMapper JSON = new ObjectMapper();
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
            Map.of("action",
                Map.of("type", "string", "enum", List.of("create_xlsx", "create_pptx", "inspect", "render_pptx")),
                "path", Map.of("type", "string", "description", "工作区内的 xlsx/pptx 文件路径；创建不得覆盖已有文件"), "spec_path",
                Map.of("type", "string", "description", "创建操作的 JSON 规格文件；格式见工具说明")),
            "required", List.of("action", "path"), "additionalProperties", false);
    }

    @Override
    public String execute(Map<String, Object> args) {
        return executeResult(args).text();
    }

    @Override
    public ToolResult executeResult(Map<String, Object> args) {
        Path temp = null;
        try {
            String action = ToolSupport.stringArg(args, "action");
            Path file = paths.resolve(ToolSupport.stringArg(args, "path"));
            if ("inspect".equals(action)) {
                return ToolResult.text(inspect(file));
            }
            if ("render_pptx".equals(action)) {
                return render(file);
            }
            if (!List.of("create_xlsx", "create_pptx").contains(action == null ? "" : action)) {
                throw new IllegalArgumentException("未知 office action");
            }
            String extension = "create_xlsx".equals(action) ? ".xlsx" : ".pptx";
            if (!file.toString().toLowerCase(java.util.Locale.ROOT).endsWith(extension)) {
                throw new IllegalArgumentException("目标扩展名必须为 " + extension);
            }
            if (Files.exists(file)) {
                throw new IllegalArgumentException("目标已存在，请选择新文件名；不会覆盖原件");
            }
            Path specFile = paths.resolve(ToolSupport.stringArg(args, "spec_path"));
            bounded(specFile, 2_000_000);
            JsonNode spec = JSON.readTree(Files.readString(specFile));
            Files.createDirectories(file.getParent());
            temp = Files.createTempFile(file.getParent(), ".pilot-office-", extension);
            if (extension.equals(".xlsx")) {
                workbook(spec, temp);
            } else {
                presentation(spec, temp);
            }
            String verification = inspect(temp);
            snapshots.beforeWrite(file);
            Files.move(temp, file);
            temp = null;
            snapshots.afterWrite(file);
            return new ToolResult(
                "已生成并重新解析 " + paths.display(file) + "\n" + verification
                    + (extension.equals(".pptx") ? "\n尚需 render_pptx 查看每页图片，结构通过不代表视觉验收。" : ""),
                List.of(paths.display(file)), List.of("office inspect " + paths.display(file) + " → exit 0"));
        } catch (Exception e) {
            return ToolResult.text("ERROR: Office 操作失败：" + e.getMessage());
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void workbook(JsonNode spec, Path target) throws Exception {
        JsonNode sheets = spec.path("sheets");
        requireArray(sheets, 1, 32, "sheets");
        int cells = 0;
        try (var book = new XSSFWorkbook()) {
            for (JsonNode item : sheets) {
                var sheet = book.createSheet(item.path("name").asText());
                JsonNode rows = item.path("rows");
                requireArray(rows, 1, 10000, "rows");
                int r = 0;
                for (JsonNode values : rows) {
                    requireArray(values, 0, 256, "row");
                    var row = sheet.createRow(r++);
                    int c = 0;
                    for (JsonNode value : values) {
                        if (++cells > 50000) {
                            throw new IllegalArgumentException("一次最多 50000 单元格");
                        }
                        var cell = row.createCell(c++);
                        if (value.isNumber()) {
                            cell.setCellValue(value.asDouble());
                        } else if (value.isBoolean()) {
                            cell.setCellValue(value.asBoolean());
                        } else if (value.isObject() && value.has("formula")) {
                            String formula = value.path("formula").asText();
                            // 不解析外部工作簿、网络或宏调用。普通文本以 = 开头也仍保存为文本。
                            if (formula.length() > 4096
                                || formula.matches("(?is).*(\\[|https?:|file:|WEBSERVICE|HYPERLINK|DDE).*")) {
                                throw new IllegalArgumentException("不支持外部引用或网络公式");
                            }
                            cell.setCellFormula(formula.startsWith("=") ? formula.substring(1) : formula);
                        } else if (!value.isNull()) {
                            if (!value.isTextual() || value.asText().length() > 32767) {
                                throw new IllegalArgumentException("单元格格式或文本长度无效");
                            }
                            cell.setCellValue(value.asText());
                        }
                    }
                }
                sheet.createFreezePane(0, 1);
                for (int col = 0; col < rows.get(0).size(); col++) {
                    sheet.setColumnWidth(col, 22 * 256);
                }
            }
            var evaluator = book.getCreationHelper().createFormulaEvaluator();
            evaluator.evaluateAll();
            for (var sheet : book) {
                for (var row : sheet) {
                    for (var cell : row) {
                        if (cell.getCellType() == CellType.FORMULA
                            && cell.getCachedFormulaResultType() == CellType.ERROR) {
                            throw new IllegalArgumentException(
                                "公式错误：" + sheet.getSheetName() + "!" + cell.getAddress());
                        }
                    }
                }
            }
            try (var out = Files.newOutputStream(target)) {
                book.write(out);
            }
        }
    }

    private static void presentation(JsonNode spec, Path target) throws Exception {
        JsonNode slides = spec.path("slides");
        requireArray(slides, 1, 40, "slides");
        try (var deck = new XMLSlideShow()) {
            deck.setPageSize(new Dimension(960, 540));
            for (JsonNode item : slides) {
                String title = item.path("title").asText();
                if (title.isBlank() || title.length() > 45) {
                    throw new IllegalArgumentException("每页标题须为 1–45 字符");
                }
                JsonNode bullets = item.path("bullets");
                requireArray(bullets, 0, 7, "bullets");
                var slide = deck.createSlide();
                var heading = slide.createTextBox();
                heading.setAnchor(new Rectangle2D.Double(48, 32, 864, 70));
                var titleRun = heading.addNewTextParagraph().addNewTextRun();
                titleRun.setText(title);
                titleRun.setFontSize(28.0);
                titleRun.setBold(true);
                titleRun.setFontColor(new Color(25, 40, 55));
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
                }
                if (heading.getTextHeight() > 70 || body.getTextHeight() > 360) {
                    throw new IllegalArgumentException("文字超出页面布局，请减少文本或拆页");
                }
            }
            try (var out = Files.newOutputStream(target)) {
                deck.write(out);
            }
        }
    }

    private String inspect(Path file) throws Exception {
        bounded(file, 20_000_000);
        String suffix = file.toString().toLowerCase(java.util.Locale.ROOT);
        List<Object> items = new ArrayList<>();
        if (suffix.endsWith(".xlsx")) {
            try (var in = Files.newInputStream(file); var book = new XSSFWorkbook(in)) {
                if (!book.getExternalLinksTable().isEmpty()) {
                    throw new IllegalArgumentException("工作簿含外部引用，不自动计算");
                }
                var formatter = new DataFormatter(java.util.Locale.ROOT);
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
                    items.add(Map.of("name", sheet.getSheetName(), "rows", sheet.getLastRowNum() + 1, "sample_cells",
                        values));
                }
            }
        } else if (suffix.endsWith(".pptx")) {
            try (var in = Files.newInputStream(file); var deck = new XMLSlideShow(in)) {
                if (deck.getSlides().size() > 40) {
                    throw new IllegalArgumentException("超过 40 页检查上限");
                }
                for (var slide : deck.getSlides()) {
                    String text = slide.getShapes().stream().filter(XSLFTextShape.class::isInstance)
                        .map(XSLFTextShape.class::cast).map(XSLFTextShape::getText)
                        .collect(java.util.stream.Collectors.joining("\n"));
                    items.add(Map.of("slide", slide.getSlideNumber(), "text", text));
                }
            }
        } else {
            throw new IllegalArgumentException("仅支持 xlsx/pptx；其他格式请使用原生软件转换后检查");
        }
        String result = JSON.writeValueAsString(Map.of("parsed", true, "items", items));
        return result.length() > 24000 ? result.substring(0, 24000) + "\n（展示已截断，不能当作完整 JSON）" : result;
    }

    private ToolResult render(Path file) throws Exception {
        bounded(file, 20_000_000);
        if (!file.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".pptx")) {
            throw new IllegalArgumentException("需要 pptx 文件");
        }
        Path directory = paths.resolve(paths.display(file) + ".preview-" + java.util.UUID.randomUUID());
        List<Attachment> images = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        try (var in = Files.newInputStream(file); var deck = new XMLSlideShow(in)) {
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
            changed, List.of("office render " + paths.display(file) + " → exit 0"), images);
    }

    private static void bounded(Path file, long limit) throws Exception {
        if (!Files.isRegularFile(file) || Files.size(file) > limit) {
            throw new IllegalArgumentException("文件不存在或超过大小限制 " + limit);
        }
    }

    private static void requireArray(JsonNode node, int min, int max, String field) {
        if (!node.isArray() || node.size() < min || node.size() > max) {
            throw new IllegalArgumentException(field + " 数量须为 " + min + "–" + max);
        }
    }
}
