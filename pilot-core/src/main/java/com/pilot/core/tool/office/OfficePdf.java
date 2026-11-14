package com.pilot.core.tool.office;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import com.pilot.core.loop.Attachment;
import com.pilot.core.tool.ToolArtifacts;
import com.pilot.core.tool.ToolResult;
import com.pilot.core.util.Sha256;
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
                            case "table" -> {
                                writer.line("-------------------- table --------------------", 9f);
                                for (List<String> row : block.rows()) {
                                    writer.line(String.join(" | ", row), 10f);
                                }
                                writer.line("----------------------------------------------", 9f);
                            }
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
            int[] range = pageRange(pagesArg, total);
            Path dir = ToolArtifacts.directory("office-pdf/" + digest(file, total));
            PDFRenderer renderer = new PDFRenderer(document);
            List<Attachment> images = new ArrayList<>();
            int rendered = 0;
            for (int page = range[0]; page <= range[1]; page++) {
                BufferedImage image = renderer.renderImageWithDPI(page - 1, DPI);
                Path png = dir.resolve("page-" + page + ".png");
                if (!ImageIO.write(image, "png", png.toFile()) || Files.size(png) > MAX_PNG_BYTES) {
                    Files.deleteIfExists(png);
                    continue;
                }
                rendered++;
                if (images.size() < 4) {
                    images.add(new Attachment(file.getFileName() + " 第 " + page + " 页", "image/png", png.toString()));
                }
            }
            String text = "已渲染 " + rendered + " 页，图片目录：" + dir
                + "。每次只附前四页，其余页用 pages 指定范围分段渲染；图片在产物目录，不能用 read_file 读取。" + "PDF 渲染不检验 Word 排版。";
            return new ToolResult(text, List.of(),
                List.of("office render_pdf " + file.getFileName() + " → " + rendered + " 页"), images);
        }
    }

    private static int[] pageRange(Object pagesArg, int total) {
        if (pagesArg == null || pagesArg.toString().isBlank()) {
            return new int[] {1, total};
        }
        String raw = pagesArg.toString().strip();
        try {
            if (raw.contains("-")) {
                String[] parts = raw.split("-", 2);
                int from = Integer.parseInt(parts[0].strip());
                int to = Integer.parseInt(parts[1].strip());
                if (from < 1 || to > total || from > to || to - from + 1 > MAX_PAGES) {
                    throw new IllegalArgumentException("页码范围无效");
                }
                return new int[] {from, to};
            }
            int page = Integer.parseInt(raw);
            if (page < 1 || page > total) {
                throw new IllegalArgumentException("页码超出范围");
            }
            return new int[] {page, page};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("pages 须为页码或范围，例如 3 或 2-5");
        }
    }

    private static String digest(Path file, int pages) throws IOException {
        var sha = Sha256.newDigest();
        sha.update(file.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
        sha.update(Long.toString(Files.size(file)).getBytes(StandardCharsets.UTF_8));
        sha.update(Integer.toString(pages).getBytes(StandardCharsets.UTF_8));
        return Sha256.finishHex(sha);
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

        private void newPage() throws IOException {
            if (stream != null) {
                stream.endText();
                stream.close();
            }
            document.addPage(new PDPage(PDRectangle.A4));
            stream = new PDPageContentStream(document, document.getPage(document.getNumberOfPages() - 1));
            stream.beginText();
            stream.setLeading(LEADING);
            stream.newLineAtOffset(LEFT, TOP);
            y = TOP;
        }

        private void line(String text, float size) throws IOException {
            stream.setFont(font, size);
            for (String part : wrap(text, size)) {
                if (y - LEADING < BOTTOM) {
                    newPage();
                    stream.setFont(font, size);
                }
                stream.showText(part);
                stream.newLine();
                y -= LEADING;
            }
        }

        private List<String> wrap(String text, float size) throws IOException {
            float maxWidth = PDRectangle.A4.getWidth() - LEFT - RIGHT;
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
                stream.endText();
                stream.close();
                stream = null;
            }
        }
    }
}
