package com.pilot.core.tool.office;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;

/**
 * 文件工作台的逐页预览：与 {@link OfficeTool} 共用同一批 POI / PDFBox 渲染能力。
 *
 * <p>PDF 与 PPTX 按原版式渲染；DOCX / XLSX 在纯 Java 下无法还原 Office 排版，
 * 因此生成结构化内容预览，并由 notice 明确告知用户「真实排版请用本机应用打开」。
 */
public final class FilePreviews {
    /** 单次预览最多生成 30 页，避免大文件拖垮会话。 */
    public static final int MAX_PAGES = 30;
    private static final float DPI = 96f;
    private static final int TEXT_WIDTH = 900;
    private static final int TEXT_HEIGHT = 1200;
    private static final int MARGIN = 48;
    private static final int LINE_HEIGHT = 26;

    private FilePreviews() {
    }

    public record Result(int pages, String notice) {
    }

    public static Result generate(Path source, Path directory) throws IOException {
        OfficeSupport.bounded(source, OfficeSupport.MAX_OFFICE_BYTES);
        Files.createDirectories(directory);
        return switch (OfficeSupport.extension(source)) {
            case ".pdf" -> pdf(source, directory);
            case ".pptx" -> pptx(source, directory);
            default -> content(source, directory);
        };
    }

    private static Result pdf(Path source, Path directory) throws IOException {
        try (PDDocument document = Loader.loadPDF(source.toFile())) {
            int total = Math.min(document.getNumberOfPages(), MAX_PAGES);
            PDFRenderer renderer = new PDFRenderer(document);
            for (int page = 1; page <= total; page++) {
                write(renderer.renderImageWithDPI(page - 1, DPI), directory.resolve("page-" + page + ".png"));
            }
            String notice =
                "按 PDF 原始版式逐页渲染" + (document.getNumberOfPages() > MAX_PAGES ? "，仅前 " + MAX_PAGES + " 页" : "");
            return new Result(total, notice);
        }
    }

    private static Result pptx(Path source, Path directory) throws IOException {
        OfficeSupport.ensureCjkFontRegistered();
        try (InputStream in = Files.newInputStream(source); XMLSlideShow deck = new XMLSlideShow(in)) {
            List<XSLFSlide> slides = deck.getSlides();
            int total = Math.min(slides.size(), MAX_PAGES);
            Dimension size = deck.getPageSize();
            if (size.width < 1 || size.height < 1 || size.width > 2000 || size.height > 2000) {
                throw new IllegalArgumentException("演示文稿页面尺寸超过渲染上限");
            }
            int page = 1;
            for (XSLFSlide slide : slides) {
                if (page > total) {
                    break;
                }
                write(renderSlide(slide, size), directory.resolve("page-" + page + ".png"));
                page++;
            }
            return new Result(total,
                "按幻灯片版式逐页渲染；字体依赖本机，请检查缺字与重叠" + (slides.size() > MAX_PAGES ? "，仅前 " + MAX_PAGES + " 页" : ""));
        }
    }

    static BufferedImage renderSlide(XSLFSlide slide, Dimension size) {
        BufferedImage image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, size.width, size.height);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            slide.draw(graphics);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /** 结构化内容预览：按可读行宽折行分页，明确不冒充 Office 排版。 */
    private static Result content(Path source, Path directory) throws IOException {
        OfficeSupport.ensureCjkFontRegistered();
        List<String> lines = wrap(OfficeSupport.extractText(source));
        int perPage = Math.max(1, (TEXT_HEIGHT - 2 * MARGIN) / LINE_HEIGHT);
        int pages = Math.min(MAX_PAGES, Math.max(1, (lines.size() + perPage - 1) / perPage));
        for (int page = 1; page <= pages; page++) {
            BufferedImage image = new BufferedImage(TEXT_WIDTH, TEXT_HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, TEXT_WIDTH, TEXT_HEIGHT);
                graphics.setColor(new Color(0x1F2937));
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Font font = new Font(Font.DIALOG, Font.PLAIN, 15);
                graphics.setFont(font);
                int y = MARGIN + LINE_HEIGHT;
                int start = (page - 1) * perPage;
                for (int index = start; index < Math.min(start + perPage, lines.size()); index++) {
                    graphics.drawString(lines.get(index), MARGIN, y);
                    y += LINE_HEIGHT;
                }
                graphics.setColor(new Color(0x6B7280));
                graphics.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
                graphics.drawString(source.getFileName() + " · 第 " + page + " / " + pages + " 页 · 内容预览", MARGIN,
                    TEXT_HEIGHT - MARGIN / 2);
            } finally {
                graphics.dispose();
            }
            write(image, directory.resolve("page-" + page + ".png"));
        }
        return new Result(pages,
            "这是内容预览，不还原 Office 版式；真实排版请点「用本机应用打开」" + (lines.size() > perPage * MAX_PAGES ? "，文本已截断" : ""));
    }

    private static List<String> wrap(String text) {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        FontMetrics metrics = probe.createGraphics().getFontMetrics(new Font(Font.DIALOG, Font.PLAIN, 15));
        int maxWidth = TEXT_WIDTH - 2 * MARGIN;
        List<String> result = new ArrayList<>();
        for (String paragraph : text.split("\\R")) {
            String value = paragraph.strip();
            if (value.isEmpty()) {
                result.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < value.length(); i++) {
                line.append(value.charAt(i));
                if (metrics.stringWidth(line.toString()) > maxWidth) {
                    line.deleteCharAt(line.length() - 1);
                    result.add(line.toString());
                    line.setLength(0);
                    i--;
                }
            }
            if (!line.isEmpty()) {
                result.add(line.toString());
            }
        }
        if (result.isEmpty()) {
            result.add("（无文本内容）");
        }
        return result;
    }

    private static void write(BufferedImage image, Path target) throws IOException {
        if (!ImageIO.write(image, "png", target.toFile())) {
            throw new IOException("无法写入预览图片：" + target.getFileName());
        }
    }
}
