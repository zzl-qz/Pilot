package com.pilot.core.tool.office;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;

import com.pilot.core.tool.ToolResult;
import com.pilot.core.workspace.PathValidator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** PDF：真实生成、逐页渲染与文本读回。中文必须嵌入可用字体，缺字体时明确拒绝。 */
final class OfficePdf {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final float LEFT = 50f;
    private static final float RIGHT = 50f;
    private static final float TOP = 790f;
    private static final float BOTTOM = 50f;
    private static final float LEADING = 16f;
    private static final float DPI = 110f;
    private static final int MAX_PAGES = 40;
    private static final long MAX_PNG_BYTES = 5L * 1024 * 1024;

    private OfficePdf() {
    }

    /** @return 字体来源说明，随生成结果一起回给模型，便于判断是否做了 CJK 嵌入。 */
    static String create(Path target, JsonNode spec, PathValidator paths) throws IOException {
        String title = OfficeSupport.text(spec, "title", 500, false);
        List<OfficeBlocks.Block> blocks = OfficeBlocks.parse(spec, "blocks");
        if (!OfficeFonts.needsEmbeddedFont(OfficeBlocks.allText(title, blocks))) {
            return write(target, title, blocks, null);
        }
        List<Path> candidates = OfficeFonts.candidates(paths, OfficeSupport.text(spec, "font_path", 500, false));
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException(OfficeFonts.missingFontHint());
        }
        // 字体存在不等于可加载、也不等于覆盖所需字形：每个候选用独立文档试生成，全失败才报错。
        IOException last = null;
        for (Path candidate : candidates) {
            try {
                return write(target, title, blocks, candidate);
            } catch (IOException e) {
                last = e;
            }
        }
        throw new IllegalArgumentException(OfficeFonts.missingFontHint() + " 已尝试 " + candidates.size() + " 个字体，最后错误："
            + (last == null ? "未知" : last.getMessage()));
    }

    private static String write(Path target, String title, List<OfficeBlocks.Block> blocks, Path fontPath)
        throws IOException {
        try (PDDocument document = new PDDocument()) {
            OfficeFonts.FontHandle handle =
                fontPath == null ? OfficeFonts.standard(document) : OfficeFonts.load(document, fontPath);
            try (OutputStream out = Files.newOutputStream(target)) {
                try {
                    PageWriter writer = new PageWriter(document, handle.font());
                    if (!title.isBlank()) {
                        writer.line(title, 18f);
                        writer.line("", 11f);
                    }
                    for (OfficeBlocks.Block block : blocks) {
                        switch (block.type()) {
                            case "heading" -> writer.line(block.text(), block.level() == 1 ? 16f : 13f);
                            case "paragraph" -> writer.line(block.text(), 11f);
                            case "bullet" -> writer.line("• " + block.text(), 11f);
                            case "page_break" -> writer.newPage();
                            case "table" -> writer.table(block.rows());
                            default -> throw new IllegalArgumentException("不支持的块类型：" + block.type());
                        }
                    }
                    writer.finish();
                    if (handle.font() instanceof PDType0Font embedded && embedded.willBeSubset()) {
                        embedded.subset();
                    }
                    document.save(out);
                } catch (IllegalArgumentException e) {
                    throw new IOException("当前字体无法编码文本（可能缺少字形）：" + e.getMessage(), e);
                }
            } finally {
                handle.close();
            }
            return "字体=" + handle.source() + (fontPath == null ? "（纯 ASCII）" : "（已嵌入）");
        }
    }

    static String inspect(Path file) throws IOException {
        OfficeSupport.bounded(file, OfficeSupport.MAX_OFFICE_BYTES);
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            int pages = document.getNumberOfPages();
            String text = "";
            if (pages > 0) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(1);
                stripper.setEndPage(Math.min(pages, 10));
                text = stripper.getText(document);
            }
            return JSON.writeValueAsString(Map.of("parsed", true, "format", "pdf", "pages", pages, "text", text));
        }
    }

    /** 逐页渲染到产物目录（按内容摘要去重），首四页作为图片回传。 */
    static ToolResult render(Path file, Object pagesArg) throws IOException {
        OfficeSupport.bounded(file, OfficeSupport.MAX_OFFICE_BYTES);
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            int total = document.getNumberOfPages();
            if (total < 1 || total > MAX_PAGES) {
                throw new IllegalArgumentException("PDF 页数须为 1–" + MAX_PAGES + "，当前 " + total);
            }
            int[] range = OfficeSupport.parsePageRange(pagesArg, total, total, MAX_PAGES, false);
            OfficeSupport.RenderedPdf result = OfficeSupport.renderPdfPages(document, file, range,
                new OfficeSupport.PdfRenderOptions("office-pdf/", DPI, MAX_PNG_BYTES, 4, false));
            String text = "已渲染 " + result.renderedPages() + " 页，图片目录：" + result.directory()
                + "。每次只附前四页，其余页用 pages 指定范围分段渲染；图片在产物目录，不能用 read_file 读取。" + "PDF 渲染不检验 Word 排版。";
            return new ToolResult(text, List.of(),
                List.of("office render_pdf " + file.getFileName() + " → " + result.renderedPages() + " 页"),
                result.images());
        }
    }

    /** 简单 A4 垂直排版：按字体实际宽度折行，超出下边距自动翻页。 */
    private static final class PageWriter {
        private final PDDocument document;
        private final PDFont font;
        private PDPageContentStream stream;
        private float y;

        private PageWriter(PDDocument document, PDFont font) throws IOException {
            this.document = document;
            this.font = font;
            newPage();
        }

        private void line(String text, float size) throws IOException {
            float step = Math.max(LEADING, size * 1.45f);
            for (String part : wrap(text, size, PDRectangle.A4.getWidth() - LEFT - RIGHT)) {
                if (y - step < BOTTOM) {
                    newPage();
                }
                stream.beginText();
                stream.setFont(font, size);
                stream.setNonStrokingColor(0.08f, 0.1f, 0.14f);
                stream.newLineAtOffset(LEFT, y);
                stream.showText(part);
                stream.endText();
                y -= step;
            }
        }

        private void table(List<List<String>> rows) throws IOException {
            if (rows == null || rows.isEmpty()) {
                return;
            }
            int cols = Math.min(8, rows.stream().mapToInt(List::size).max().orElse(1));
            float tableW = PDRectangle.A4.getWidth() - LEFT - RIGHT;
            float colW = tableW / cols;
            float fontSize = 9.5f;
            float lineStep = 13.5f;
            float padX = 6f;
            float padY = 5f;
            y -= 4f;

            for (int r = 0; r < rows.size(); r++) {
                List<String> row = rows.get(r);
                boolean header = r == 0;
                List<List<String>> wrappedCells = new ArrayList<>(cols);
                int maxLines = 1;
                for (int c = 0; c < cols; c++) {
                    String val = c < row.size() ? row.get(c) : "";
                    List<String> wrapped = wrap(val, fontSize, Math.max(24f, colW - padX * 2));
                    wrappedCells.add(wrapped);
                    maxLines = Math.max(maxLines, wrapped.size());
                }
                float rowH = padY * 2 + maxLines * lineStep;
                if (y - rowH < BOTTOM) {
                    newPage();
                }
                float topY = y + 9f;
                float bottomY = topY - rowH;
                if (header) {
                    stream.setNonStrokingColor(0.95f, 0.96f, 0.97f);
                    stream.addRect(LEFT, bottomY, tableW, rowH);
                    stream.fill();
                }
                stream.setStrokingColor(header ? 0.78f : 0.86f, header ? 0.80f : 0.88f, header ? 0.84f : 0.90f);
                stream.setLineWidth(0.6f);
                stream.addRect(LEFT, bottomY, tableW, rowH);
                stream.stroke();
                for (int c = 1; c < cols; c++) {
                    float cx = LEFT + c * colW;
                    stream.moveTo(cx, bottomY);
                    stream.lineTo(cx, topY);
                    stream.stroke();
                }
                for (int c = 0; c < cols; c++) {
                    float cellLeft = LEFT + c * colW;
                    List<String> cellLines = wrappedCells.get(c);
                    for (int li = 0; li < cellLines.size(); li++) {
                        String txt = cellLines.get(li);
                        stream.beginText();
                        stream.setFont(font, fontSize);
                        stream.setNonStrokingColor(header ? 0.07f : 0.20f, header ? 0.09f : 0.24f,
                            header ? 0.15f : 0.30f);
                        stream.newLineAtOffset(cellLeft + padX, topY - padY - 8.5f - li * lineStep);
                        stream.showText(txt);
                        stream.endText();
                    }
                }
                y -= rowH;
            }
            y -= 8f;
        }

        private void newPage() throws IOException {
            if (stream != null) {
                stream.close();
            }
            document.addPage(new PDPage(PDRectangle.A4));
            stream = new PDPageContentStream(document, document.getPage(document.getNumberOfPages() - 1));
            y = TOP;
        }

        private List<String> wrap(String text, float size, float maxWidth) throws IOException {
            List<String> lines = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch == '\n') {
                    lines.add(current.toString());
                    current.setLength(0);
                    continue;
                }
                String candidate = current.toString() + ch;
                if (!current.isEmpty() && font.getStringWidth(candidate) / 1000f * size > maxWidth) {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                current.append(ch);
            }
            lines.add(current.toString());
            return lines;
        }

        private void finish() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }
    }
}
