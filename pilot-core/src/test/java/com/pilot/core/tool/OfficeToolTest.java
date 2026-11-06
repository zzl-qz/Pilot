package com.pilot.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.workspace.PathValidator;

class OfficeToolTest {
    @TempDir
    Path root;

    private OfficeTool tool() {
        return new OfficeTool(new PathValidator(root), SnapshotSink.none());
    }

    @Test
    void createsWorkbookRecalculatesAndPreservesText() throws Exception {
        Files.writeString(root.resolve("spec.json"), """
            {"sheets":[{"name":"收入","rows":[["项目","金额"],["A",10],["=不是公式",20],["总计",{"formula":"SUM(B2:B3)"}]]}]}
            """);
        var result = tool().executeResult(Map.of("action", "create_xlsx", "path", "结果.xlsx", "spec_path", "spec.json"));
        assertFalse(result.text().startsWith("ERROR"), result.text());
        assertEquals(List.of("结果.xlsx"), result.changedPaths());
        try (var in = Files.newInputStream(root.resolve("结果.xlsx")); var workbook = new XSSFWorkbook(in)) {
            var sheet = workbook.getSheet("收入");
            assertEquals(30.0, sheet.getRow(3).getCell(1).getNumericCellValue());
            assertEquals(CellType.STRING, sheet.getRow(2).getCell(0).getCellType());
        }
        String inspected = tool().execute(Map.of("action", "inspect", "path", "结果.xlsx"));
        assertTrue(inspected.contains("SUM(B2:B3)"));
        assertTrue(inspected.contains("30"));
        assertTrue(tool().execute(Map.of("action", "create_xlsx", "path", "结果.xlsx", "spec_path", "spec.json"))
            .startsWith("ERROR"));
    }

    @Test
    void rejectsFormulaErrorsAndEscapesWithoutDeliveringFile() throws Exception {
        Files.writeString(root.resolve("spec.json"),
            "{\"sheets\":[{\"name\":\"bad\",\"rows\":[[{\"formula\":\"1/0\"}]]}]}");
        assertTrue(tool().execute(Map.of("action", "create_xlsx", "path", "bad.xlsx", "spec_path", "spec.json"))
            .startsWith("ERROR"));
        assertFalse(Files.exists(root.resolve("bad.xlsx")));
        assertTrue(tool().execute(Map.of("action", "create_xlsx", "path", "../outside.xlsx", "spec_path", "spec.json"))
            .startsWith("ERROR"));
        Files.writeString(root.resolve("fake.xlsx"), "not a zip");
        assertTrue(tool().execute(Map.of("action", "inspect", "path", "fake.xlsx")).startsWith("ERROR"));
    }

    @Test
    void createsReopensAndRendersEverySlide() throws Exception {
        Files.writeString(root.resolve("slides.json"),
            """
                {"slides":[{"title":"Task evidence","bullets":["Reopen the workbook","Check totals"]},{"title":"Next steps","bullets":["Review every slide"]}]}
                """);
        String created =
            tool().execute(Map.of("action", "create_pptx", "path", "deck.pptx", "spec_path", "slides.json"));
        assertFalse(created.startsWith("ERROR"), created);
        try (var in = Files.newInputStream(root.resolve("deck.pptx")); var deck = new XMLSlideShow(in)) {
            assertEquals(2, deck.getSlides().size());
        }
        var rendered = tool().executeResult(Map.of("action", "render_pptx", "path", "deck.pptx"));
        assertFalse(rendered.text().startsWith("ERROR"), rendered.text());
        assertEquals(2, rendered.images().size());
        for (var image : rendered.images()) {
            var png = ImageIO.read(Path.of(image.path()).toFile());
            assertEquals(960, png.getWidth());
            assertEquals(540, png.getHeight());
        }
    }
}
