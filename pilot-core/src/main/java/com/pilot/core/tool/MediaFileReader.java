package com.pilot.core.tool;

import com.pilot.core.loop.Attachment;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code read_file} 的图片与 PDF 分支：图片原样交给模型，PDF 抽取文本，扫描件按页渲染成图片。
 *
 * <p>只做「把文件内容变成模型能看的东西」，路径校验由调用方的 {@code PathValidator} 完成。
 */
final class MediaFileReader {
    static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    static final long MAX_PDF_BYTES = 32L * 1024 * 1024;
    private static final int DEFAULT_PDF_PAGES = 10;
    private static final int MAX_PDF_PAGES = 30;
    private static final int MAX_PDF_TEXT_CHARS = 30_000;
    private static final int MAX_RENDERED_PAGES = 3;
    private static final float RENDER_DPI = 110f;

    private static final Map<String, String> IMAGE_TYPES = Map.of(
            "png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg",
            "gif", "image/gif", "webp", "image/webp");

    private MediaFileReader() {
    }

    static boolean isImage(Path file) {
        return IMAGE_TYPES.containsKey(extension(file));
    }

    static boolean isPdf(Path file) {
        return "pdf".equals(extension(file));
    }

    static ToolResult readImage(Path file, String display) throws IOException {
        long size = Files.size(file);
        if (size > MAX_IMAGE_BYTES) {
            return ToolResult.text("ERROR: 图片 " + display + " 有 " + size / 1024
                    + " KB，超过 " + MAX_IMAGE_BYTES / 1024 + " KB 上限；请先缩小或裁剪后再读取。");
        }
        if (size == 0) {
            return ToolResult.text("ERROR: 图片文件为空: " + display);
        }
        String mime = IMAGE_TYPES.get(extension(file));
        String text = "图片 " + display + "（" + mime + "，" + Math.max(1, size / 1024) + " KB）。"
                + "图片内容附在本批工具结果之后的消息里，请直接观察，不要再用 read_file 重复读取。";
        return ToolResult.withImages(text, List.of(new Attachment(file.getFileName().toString(), mime,
                file.toAbsolutePath().toString())));
    }

    static ToolResult readPdf(Path file, String display, Object pagesArg) throws IOException {
        long size = Files.size(file);
        if (size > MAX_PDF_BYTES) {
            return ToolResult.text("ERROR: PDF " + display + " 超过 " + MAX_PDF_BYTES / 1024 / 1024 + " MB 上限");
        }
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            int total = document.getNumberOfPages();
            if (total == 0) return ToolResult.text("ERROR: PDF 没有页面: " + display);
            int[] range;
            try {
                range = pageRange(pagesArg, total);
            } catch (IllegalArgumentException e) {
                return ToolResult.text("ERROR: " + e.getMessage() + "（PDF 共 " + total + " 页）");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(range[0]);
            stripper.setEndPage(range[1]);
            String text = stripper.getText(document).strip();

            StringBuilder out = new StringBuilder("PDF ").append(display).append("：共 ").append(total)
                    .append(" 页，本次读取第 ").append(range[0]).append('-').append(range[1]).append(" 页");
            if (!text.isBlank()) {
                boolean cut = text.length() > MAX_PDF_TEXT_CHARS;
                out.append("\n").append(cut ? text.substring(0, MAX_PDF_TEXT_CHARS) : text);
                if (cut) out.append("\n… 文本已截断，缩小 pages 范围继续读取 …");
                if (range[1] < total) {
                    out.append("\n… 后续页未读取，用 pages=\"").append(range[1] + 1).append('-')
                            .append(Math.min(total, range[1] + DEFAULT_PDF_PAGES)).append("\" 继续 …");
                }
                return ToolResult.text(out.toString());
            }
            // 没有可抽取文本：多半是扫描件，渲染前几页让模型直接看图
            List<Attachment> images = render(document, file, range);
            if (images.isEmpty()) {
                return ToolResult.text("ERROR: PDF 没有可抽取的文本，且页面渲染失败: " + display);
            }
            out.append("\n没有可抽取的文本（可能是扫描件）。已渲染 ").append(images.size())
                    .append(" 页为图片，附在本批工具结果之后的消息里。");
            return ToolResult.withImages(out.toString(), images);
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            return ToolResult.text("ERROR: PDF 已加密，无法读取: " + display);
        }
    }

    private static List<Attachment> render(PDDocument document, Path source, int[] range) throws IOException {
        PDFRenderer renderer = new PDFRenderer(document);
        String key = digest(source, document.getNumberOfPages());
        Path dir = ToolArtifacts.directory("pdf/" + key);
        List<Attachment> images = new ArrayList<>();
        for (int page = range[0]; page <= range[1] && images.size() < MAX_RENDERED_PAGES; page++) {
            BufferedImage image = renderer.renderImageWithDPI(page - 1, RENDER_DPI);
            Path target = dir.resolve("page-" + page + ".png");
            if (!ImageIO.write(image, "png", target.toFile()) || Files.size(target) > MAX_IMAGE_BYTES) {
                Files.deleteIfExists(target);
                continue;
            }
            images.add(new Attachment(source.getFileName() + " 第 " + page + " 页", "image/png",
                    target.toString()));
        }
        return images;
    }

    /** 同一份文件内容得到同一个目录，重复读取不会堆积产物。 */
    private static String digest(Path source, int pages) throws IOException {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(source.toAbsolutePath().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            sha.update(Long.toString(Files.size(source)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            sha.update(Long.toString(Files.getLastModifiedTime(source).toMillis())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            sha.update((byte) pages);
            return HexFormat.of().formatHex(sha.digest()).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 解析 {@code "3"}、{@code "2-5"}；缺省读前 10 页，单次最多 30 页。 */
    static int[] pageRange(Object value, int total) {
        if (value == null || value.toString().isBlank()) {
            return new int[]{1, Math.min(total, DEFAULT_PDF_PAGES)};
        }
        String text = value.toString().strip();
        int start;
        int end;
        try {
            int dash = text.indexOf('-');
            if (dash < 0) {
                start = end = Integer.parseInt(text);
            } else {
                start = Integer.parseInt(text.substring(0, dash).strip());
                end = Integer.parseInt(text.substring(dash + 1).strip());
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("pages 必须是页码或范围，例如 \"3\" 或 \"2-5\"");
        }
        if (start < 1 || end < start || start > total) {
            throw new IllegalArgumentException("pages 超出范围: " + text);
        }
        end = Math.min(end, Math.min(total, start + MAX_PDF_PAGES - 1));
        return new int[]{start, end};
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }
}
