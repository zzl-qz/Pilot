package com.pilot.core.tool.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.tool.ToolArtifacts;
import com.pilot.core.tool.ToolResult;
import com.pilot.core.workspace.PathValidator;

class ReadFileMediaTest {
    @TempDir
    Path workspace;
    @TempDir
    Path artifacts;

    private ReadFileTool tool;
    private String previousArtifacts;

    @BeforeEach
    void setUp() {
        previousArtifacts = System.getProperty(ToolArtifacts.PROPERTY);
        System.setProperty(ToolArtifacts.PROPERTY, artifacts.toString());
        tool = new ReadFileTool(new PathValidator(workspace));
    }

    @AfterEach
    void tearDown() {
        if (previousArtifacts == null) {
            System.clearProperty(ToolArtifacts.PROPERTY);
        } else {
            System.setProperty(ToolArtifacts.PROPERTY, previousArtifacts);
        }
    }

    private Path png(String name, int size) throws IOException {
        Path file = workspace.resolve(name);
        Files.createDirectories(file.getParent());
        ImageIO.write(new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        return file;
    }

    @Test
    void imagesComeBackAsImageAttachments() throws IOException {
        Path file = png("shots/home.png", 8);

        ToolResult result = tool.executeResult(Map.of("path", "shots/home.png"));

        assertTrue(result.text().contains("图片 shots/home.png（image/png"), result.text());
        assertEquals(1, result.images().size());
        assertEquals("image/png", result.images().getFirst().mimeType());
        assertEquals(file.toRealPath().toString(), Path.of(result.images().getFirst().path()).toRealPath().toString());
        assertTrue(result.images().getFirst().image());
    }

    @Test
    void textOnlyExecuteDescribesTheImageWithoutBinaryNoise() throws IOException {
        png("a.png", 4);

        String text = tool.execute(Map.of("path", "a.png"));

        assertTrue(text.startsWith("ERROR: 二进制文件"), text);
    }

    @Test
    void oversizedImagesAreRejectedWithAnActionableMessage() throws IOException {
        Path big = workspace.resolve("big.png");
        Files.write(big, new byte[(int)MediaFileReader.MAX_IMAGE_BYTES + 1]);

        ToolResult result = tool.executeResult(Map.of("path", "big.png"));

        assertTrue(result.text().startsWith("ERROR: 图片 big.png"), result.text());
        assertTrue(result.images().isEmpty());
    }

    @Test
    void pdfTextIsExtractedByPageRange() throws IOException {
        Path pdf = workspace.resolve("spec.pdf");
        try (PDDocument document = new PDDocument()) {
            for (String text : new String[] {"Alpha page", "Bravo page", "Charlie page"}) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                    content.newLineAtOffset(72, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            document.save(pdf.toFile());
        }

        ToolResult all = tool.executeResult(Map.of("path", "spec.pdf"));
        assertTrue(
            all.text().contains("共 3 页") && all.text().contains("Alpha page") && all.text().contains("Charlie page"),
            all.text());
        assertTrue(all.images().isEmpty());

        ToolResult second = tool.executeResult(Map.of("path", "spec.pdf", "pages", "2"));
        assertTrue(second.text().contains("Bravo page") && !second.text().contains("Alpha page"), second.text());

        assertTrue(tool.executeResult(Map.of("path", "spec.pdf", "pages", "9")).text().startsWith("ERROR: pages 超出范围"));
        assertTrue(tool.executeResult(Map.of("path", "spec.pdf", "pages", "abc")).text().startsWith("ERROR: pages 必须"));
    }

    @Test
    void scannedPdfWithoutTextIsRenderedToImages() throws IOException {
        Path pdf = workspace.resolve("scan.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(pdf.toFile());
        }

        ToolResult result = tool.executeResult(Map.of("path", "scan.pdf"));

        assertTrue(result.text().contains("没有可抽取的文本"), result.text());
        assertEquals(1, result.images().size());
        Path rendered = Path.of(result.images().getFirst().path());
        assertTrue(Files.isRegularFile(rendered));
        assertTrue(rendered.startsWith(artifacts), rendered.toString());
        assertFalse(Files.size(rendered) == 0);
    }

    @Test
    void plainTextFilesAreUnaffected() throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "hello\n");

        ToolResult result = tool.executeResult(Map.of("path", "a.txt"));

        assertEquals("1 | hello\n… 显示第 1 到第 1 行，文件共 1 行 …", result.text());
        assertTrue(result.images().isEmpty());
    }
}
