package com.pilot.core.tool.office;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Word 文档：真实 OOXML，仅使用 POI XWPF，不依赖本机 Office。 */
final class OfficeDocx {

    private static final ObjectMapper JSON = new ObjectMapper();

    private OfficeDocx() {
    }

    static void create(Path target, JsonNode spec) throws IOException {
        OfficeSupport.hardenZip();
        String title = OfficeSupport.text(spec, "title", 500, false);
        List<OfficeBlocks.Block> blocks = OfficeBlocks.parse(spec, "blocks");
        try (XWPFDocument document = new XWPFDocument(); OutputStream out = Files.newOutputStream(target)) {
            if (!title.isBlank()) {
                paragraph(document, title, true, 20);
            }
            append(document, blocks);
            document.write(out);
        }
    }

    static void edit(Path source, Path target, JsonNode spec) throws IOException {
        OfficeSupport.hardenZip();
        OfficeSupport.bounded(source, OfficeSupport.MAX_OFFICE_BYTES);
        List<OfficeBlocks.Block> blocks = OfficeBlocks.parse(spec, "append");
        try (InputStream in = Files.newInputStream(source); XWPFDocument document = new XWPFDocument(in);
            OutputStream out = Files.newOutputStream(target)) {
            append(document, blocks);
            document.write(out);
        }
    }

    static String inspect(Path file) throws IOException {
        OfficeSupport.hardenZip();
        OfficeSupport.bounded(file, OfficeSupport.MAX_OFFICE_BYTES);
        int paragraphs;
        int tables;
        try (InputStream in = Files.newInputStream(file); XWPFDocument document = new XWPFDocument(in)) {
            paragraphs = document.getParagraphs().size();
            tables = document.getTables().size();
        }
        return JSON.writeValueAsString(Map.of("parsed", true, "format", "docx", "paragraphs", paragraphs, "tables",
            tables, "text", OfficeSupport.extractText(file)));
    }

    private static void append(XWPFDocument document, List<OfficeBlocks.Block> blocks) {
        for (OfficeBlocks.Block block : blocks) {
            switch (block.type()) {
                case "heading" -> paragraph(document, block.text(), true, block.level() == 1 ? 16 : 14);
                case "paragraph" -> paragraph(document, block.text(), false, 11);
                case "bullet" -> paragraph(document, "• " + block.text(), false, 11);
                case "page_break" -> document.createParagraph().setPageBreak(true);
                case "table" -> table(document, block.rows());
                default -> throw new IllegalArgumentException("不支持的块类型：" + block.type());
            }
        }
    }

    private static void paragraph(XWPFDocument document, String text, boolean bold, int size) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingAfter(size >= 16 ? 160 : 100);
        paragraph.setSpacingBetween(1.25);
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(bold);
        run.setFontSize(size);
        run.setColor(bold ? "111827" : "1F2937");
    }

    private static void table(XWPFDocument document, List<List<String>> rows) {
        int columns = rows.stream().mapToInt(List::size).max().orElse(1);
        var table = document.createTable(rows.size(), columns);
        table.setWidth("100%");
        table.setCellMargins(100, 140, 100, 140);
        for (int r = 0; r < rows.size(); r++) {
            List<String> values = rows.get(r);
            boolean header = r == 0;
            for (int c = 0; c < columns; c++) {
                var cell = table.getRow(r).getCell(c);
                if (header) {
                    cell.setColor("F3F4F6");
                }
                String value = c < values.size() ? values.get(c) : "";
                var p = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().getFirst();
                p.setSpacingAfter(40);
                var run = p.createRun();
                run.setText(value);
                run.setBold(header);
                run.setFontSize(10);
                run.setColor(header ? "111827" : "374151");
            }
        }
    }
}
