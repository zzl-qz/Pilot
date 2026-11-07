package com.pilot.core.tool;

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
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(bold);
        run.setFontSize(size);
    }

    private static void table(XWPFDocument document, List<List<String>> rows) {
        int columns = rows.stream().mapToInt(List::size).max().orElse(1);
        var table = document.createTable(rows.size(), columns);
        for (int r = 0; r < rows.size(); r++) {
            List<String> values = rows.get(r);
            for (int c = 0; c < columns; c++) {
                table.getRow(r).getCell(c).setText(c < values.size() ? values.get(c) : "");
            }
        }
    }
}
