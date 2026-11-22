package com.pilot.core.tool.office;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.poi.xslf.usermodel.XMLSlideShow;

import tools.jackson.databind.JsonNode;

/** Creates and edits PPTX presentations with slide-layout validation. */
public final class OfficePresentationRenderer {

    private OfficePresentationRenderer() {
    }

    public static void create(JsonNode spec, Path target) throws Exception {
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

    public static void edit(Path source, Path target, JsonNode spec) throws Exception {
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
}
