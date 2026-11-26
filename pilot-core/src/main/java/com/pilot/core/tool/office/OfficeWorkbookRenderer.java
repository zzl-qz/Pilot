package com.pilot.core.tool.office;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import tools.jackson.databind.JsonNode;

/** Creates and edits XLSX workbooks with formula validation. */
public final class OfficeWorkbookRenderer {

    private OfficeWorkbookRenderer() {
    }

    public static void create(JsonNode spec, Path target) throws Exception {
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

    public static void edit(Path source, Path target, JsonNode spec) throws Exception {
        OfficeSupport.hardenZip();
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
        for (var sheet : book) {
            for (var row : sheet) {
                for (var cell : row) {
                    if (cell.getCellType() == CellType.FORMULA) {
                        try {
                            CellType evaluated = evaluator.evaluateFormulaCell(cell);
                            if (evaluated == CellType.ERROR) {
                                throw new IllegalArgumentException(
                                    "公式错误：" + sheet.getSheetName() + "!" + cell.getAddress());
                            }
                        } catch (IllegalArgumentException failure) {
                            if (failure.getMessage() != null && failure.getMessage().startsWith("公式错误：")) {
                                throw failure;
                            }
                        } catch (RuntimeException | StackOverflowError failure) {
                            // 单个循环引用或异常公式降级保留公式原文与既有缓存，不中断整本工作簿处理。
                        }
                    }
                }
            }
        }
    }
}
