package com.pilot.core.tool.office;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;

/** docx 与 pdf 共用的内容块模型；同一份 spec 可以产出两种文件。 */
final class OfficeBlocks {

    record Block(String type, String text, int level, List<List<String>> rows) {
    }

    private OfficeBlocks() {
    }

    static List<Block> parse(JsonNode spec, String field) {
        JsonNode items = spec.path(field);
        OfficeSupport.requireArray(items, 1, OfficeSupport.MAX_BLOCKS, field);
        List<Block> blocks = new ArrayList<>();
        int cells = 0;
        for (JsonNode item : items) {
            String type = OfficeSupport.text(item, "type", 32, true);
            switch (type) {
                case "heading" -> {
                    int level = item.path("level").isNumber() ? item.path("level").asInt() : 1;
                    if (level < 1 || level > 3) {
                        throw new IllegalArgumentException("heading level 须为 1–3");
                    }
                    blocks.add(new Block(type, OfficeSupport.text(item, "text", OfficeSupport.MAX_TEXT_LENGTH, true),
                        level, List.of()));
                }
                case "paragraph", "bullet" -> blocks.add(new Block(type,
                    OfficeSupport.text(item, "text", OfficeSupport.MAX_TEXT_LENGTH, true), 1, List.of()));
                case "table" -> {
                    JsonNode rows = item.path("rows");
                    OfficeSupport.requireArray(rows, 1, 500, "table.rows");
                    List<List<String>> values = new ArrayList<>();
                    for (JsonNode row : rows) {
                        OfficeSupport.requireArray(row, 1, 32, "table.row");
                        List<String> cellsInRow = new ArrayList<>();
                        for (JsonNode cell : row) {
                            if (!cell.isTextual() || cell.asText().length() > 2_000) {
                                throw new IllegalArgumentException("表格单元格必须是字符串且不超过 2000 字符");
                            }
                            cellsInRow.add(cell.asText());
                        }
                        values.add(cellsInRow);
                        cells += cellsInRow.size();
                    }
                    if (cells > OfficeSupport.MAX_TABLE_CELLS) {
                        throw new IllegalArgumentException("表格单元格总数超过 " + OfficeSupport.MAX_TABLE_CELLS);
                    }
                    blocks.add(new Block(type, "", 1, values));
                }
                case "page_break" -> blocks.add(new Block(type, "", 1, List.of()));
                default -> throw new IllegalArgumentException("不支持的块类型：" + type);
            }
        }
        return blocks;
    }

    static String allText(String title, List<Block> blocks) {
        StringBuilder out = new StringBuilder(title == null ? "" : title);
        for (Block block : blocks) {
            out.append(block.text());
            for (List<String> row : block.rows()) {
                out.append(String.join(" ", row));
            }
        }
        return out.toString();
    }
}
