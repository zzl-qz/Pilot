package com.pilot.core.tool.office;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.ObjectMapper;

class OfficePresentationRendererTest {
    @TempDir
    Path directory;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void createsAndEditsPresentationWithoutToolDependencies() throws Exception {
        Path source = directory.resolve("source.pptx");
        Path target = directory.resolve("edited.pptx");
        OfficePresentationRenderer.create(json.readTree("""
            {"slides":[{"title":"First","bullets":["One"]}]}
            """), source);
        OfficePresentationRenderer.edit(source, target, json.readTree("""
            {"append_slides":[{"title":"Second","bullets":["Two"]}]}
            """));

        try (InputStream in = Files.newInputStream(target); var deck = new XMLSlideShow(in)) {
            assertEquals(2, deck.getSlides().size());
            String text = deck.getSlides().get(1).getShapes().stream().filter(XSLFTextShape.class::isInstance)
                .map(XSLFTextShape.class::cast).map(XSLFTextShape::getText).collect(Collectors.joining("\n"));
            Assertions.assertTrue(text.contains("Second"));
        }
    }

    @Test
    void rejectsOutOfBoundsSlideContent() throws Exception {
        Path target = directory.resolve("invalid.pptx");
        assertThrows(IllegalArgumentException.class,
            () -> OfficePresentationRenderer.create(
                json.readTree(
                    """
                        {"slides":[{"title":"A title that is deliberately longer than the allowed forty-five characters","bullets":[]}]}
                        """),
                target));
    }
}
