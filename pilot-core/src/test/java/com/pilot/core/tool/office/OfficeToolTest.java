package com.pilot.core.tool.office;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
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

import com.pilot.core.tool.SnapshotSink;
import com.pilot.core.tool.ToolResult;
import com.pilot.core.tool.file.ReadFileTool;
import com.pilot.core.workspace.PathValidator;

class OfficeToolTest {
    @TempDir
    Path root;

    private OfficeTool tool() {
        return new OfficeTool(new PathValidator(root), SnapshotSink.none());
    }

    private void spec(String name, String json) throws Exception {
        Files.writeString(root.resolve(name), json);
    }

    private ToolResult run(Map<String, Object> args) {
        return tool().executeResult(args);
    }

    private void created(String action, String path, String specPath) {
        ToolResult result = run(Map.of("action", action, "path", path, "spec_path", specPath));
        assertFalse(result.text().startsWith("ERROR"), result.text());
    }

    @Test
    void createsWorkbookRecalculatesAndPreservesText() throws Exception {
        spec("spec.json", """
            {"sheets":[{"name":"收入","rows":[["项目","金额"],["A",10],["=不是公式",20],["总计",{"formula":"SUM(B2:B3)"}]]}]}
            """);
        var result = run(Map.of("action", "create_xlsx", "path", "结果.xlsx", "spec_path", "spec.json"));
        assertFalse(result.text().startsWith("ERROR"), result.text());
        assertEquals(List.of("结果.xlsx"), result.changedPaths());
        try (InputStream in = Files.newInputStream(root.resolve("结果.xlsx")); var workbook = new XSSFWorkbook(in)) {
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
        spec("spec.json", "{\"sheets\":[{\"name\":\"bad\",\"rows\":[[{\"formula\":\"1/0\"}]]}]}");
        assertTrue(run(Map.of("action", "create_xlsx", "path", "bad.xlsx", "spec_path", "spec.json")).text()
            .startsWith("ERROR"));
        assertFalse(Files.exists(root.resolve("bad.xlsx")));
        assertTrue(run(Map.of("action", "create_xlsx", "path", "../outside.xlsx", "spec_path", "spec.json")).text()
            .startsWith("ERROR"));
        Files.writeString(root.resolve("fake.xlsx"), "not a zip");
        assertTrue(tool().execute(Map.of("action", "inspect", "path", "fake.xlsx")).startsWith("ERROR"));
    }

    @Test
    void createsReopensAndRendersEverySlide() throws Exception {
        spec("slides.json",
            """
                {"slides":[{"title":"Task evidence","bullets":["Reopen the workbook","Check totals"]},{"title":"Next steps","bullets":["Review every slide"]}]}
                """);
        created("create_pptx", "deck.pptx", "slides.json");
        try (InputStream in = Files.newInputStream(root.resolve("deck.pptx")); var deck = new XMLSlideShow(in)) {
            assertEquals(2, deck.getSlides().size());
        }
        var rendered = run(Map.of("action", "render_pptx", "path", "deck.pptx"));
        assertFalse(rendered.text().startsWith("ERROR"), rendered.text());
        assertEquals(2, rendered.images().size());
        for (var image : rendered.images()) {
            var png = ImageIO.read(Path.of(image.path()).toFile());
            assertEquals(960, png.getWidth());
            assertEquals(540, png.getHeight());
        }
    }

    @Test
    void createsDocxAndCompanionPdfFromOneSpec() throws Exception {
        spec("doc.json",
            """
                {"title":"Quarterly Report","blocks":[{"type":"heading","text":"Background","level":1},{"type":"paragraph","text":"Conclusion"},{"type":"bullet","text":"Key point"},{"type":"table","rows":[["Item","Value"],["Revenue","100"]]},{"type":"page_break"},{"type":"paragraph","text":"Page two"}],"pdf_path":"report.pdf"}
                """);
        var result = run(Map.of("action", "create_docx", "path", "report.docx", "spec_path", "doc.json"));
        assertFalse(result.text().startsWith("ERROR"), result.text());
        assertEquals(2, result.changedPaths().size());
        assertTrue(Files.exists(root.resolve("report.docx")));
        assertTrue(Files.exists(root.resolve("report.pdf")));
        String docx = tool().execute(Map.of("action", "inspect", "path", "report.docx"));
        assertTrue(docx.contains("Conclusion"));
        assertTrue(docx.contains("\"format\":\"docx\""));
        String pdf = tool().execute(Map.of("action", "inspect", "path", "report.pdf"));
        assertTrue(pdf.contains("\"format\":\"pdf\""));
        assertTrue(pdf.contains("Page two"));
    }

    @Test
    void createsPdfAndRendersEveryPage() throws Exception {
        spec("pdf.json",
            """
                {"title":"Report","blocks":[{"type":"paragraph","text":"Hello"},{"type":"page_break"},{"type":"paragraph","text":"World"}]}
                """);
        created("create_pdf", "report.pdf", "pdf.json");
        String inspected = tool().execute(Map.of("action", "inspect", "path", "report.pdf"));
        assertTrue(inspected.contains("\"pages\":2"));
        assertTrue(inspected.contains("Hello"));
        var rendered = run(Map.of("action", "render_pdf", "path", "report.pdf"));
        assertFalse(rendered.text().startsWith("ERROR"), rendered.text());
        assertEquals(2, rendered.images().size());
        for (var image : rendered.images()) {
            assertTrue(ImageIO.read(Path.of(image.path()).toFile()).getWidth() > 0);
        }
        var onePage = run(Map.of("action", "render_pdf", "path", "report.pdf", "pages", "2"));
        assertEquals(1, onePage.images().size());
    }

    @Test
    void chinesePdfNeedsEmbeddedFontOrClearError() throws Exception {
        spec("cjk.json", """
            {"title":"中文报告","blocks":[{"type":"paragraph","text":"中文内容"}]}
            """);
        var result = run(Map.of("action", "create_pdf", "path", "cjk.pdf", "spec_path", "cjk.json"));
        if (result.text().startsWith("ERROR")) {
            // 没有可用字体、或字体无法加载时都必须给出可执行的替代操作，不能静默交付缺字 PDF。
            assertTrue(result.text().contains("font_path"), result.text());
            assertFalse(Files.exists(root.resolve("cjk.pdf")));
        } else {
            assertTrue(Files.exists(root.resolve("cjk.pdf")));
            assertTrue(OfficePdf.inspect(root.resolve("cjk.pdf")).contains("\"pages\":1"));
        }
    }

    @Test
    void editsXlsxIntoNewFileWithoutOverwritingSource() throws Exception {
        spec("base.json", "{\"sheets\":[{\"name\":\"S\",\"rows\":[[\"A\",1],[\"B\",2]]}]}");
        created("create_xlsx", "base.xlsx", "base.json");
        spec("edit.json", """
            {"cells":[{"sheet":"S","row":0,"col":1,"value":99}],"append_rows":[{"sheet":"S","rows":[["C",3]]}]}
            """);
        var result = run(
            Map.of("action", "edit_xlsx", "path", "edited.xlsx", "spec_path", "edit.json", "source_path", "base.xlsx"));
        assertFalse(result.text().startsWith("ERROR"), result.text());
        try (InputStream in = Files.newInputStream(root.resolve("base.xlsx")); var book = new XSSFWorkbook(in)) {
            assertEquals(1.0, book.getSheet("S").getRow(0).getCell(1).getNumericCellValue());
        }
        try (InputStream in = Files.newInputStream(root.resolve("edited.xlsx")); var book = new XSSFWorkbook(in)) {
            assertEquals(99.0, book.getSheet("S").getRow(0).getCell(1).getNumericCellValue());
            assertEquals("C", book.getSheet("S").getRow(2).getCell(0).getStringCellValue());
        }
    }

    @Test
    void editsDocxAndPptxIntoNewFiles() throws Exception {
        spec("doc.json", "{\"blocks\":[{\"type\":\"paragraph\",\"text\":\"Original\"}]}");
        created("create_docx", "base.docx", "doc.json");
        spec("edit-doc.json", "{\"append\":[{\"type\":\"paragraph\",\"text\":\"Added\"}]}");
        var docResult = run(Map.of("action", "edit_docx", "path", "edited.docx", "spec_path", "edit-doc.json",
            "source_path", "base.docx"));
        assertFalse(docResult.text().startsWith("ERROR"), docResult.text());
        assertTrue(OfficeSupport.extractText(root.resolve("edited.docx")).contains("Added"));
        assertTrue(OfficeSupport.extractText(root.resolve("base.docx")).contains("Original"));
        assertFalse(OfficeSupport.extractText(root.resolve("base.docx")).contains("Added"));

        spec("slides.json", "{\"slides\":[{\"title\":\"First\",\"bullets\":[\"One\"]}]}");
        created("create_pptx", "base.pptx", "slides.json");
        spec("edit-slides.json", "{\"append_slides\":[{\"title\":\"Second\",\"bullets\":[\"Two\"]}]}");
        var slideResult = run(Map.of("action", "edit_pptx", "path", "edited.pptx", "spec_path", "edit-slides.json",
            "source_path", "base.pptx"));
        assertFalse(slideResult.text().startsWith("ERROR"), slideResult.text());
        try (InputStream in = Files.newInputStream(root.resolve("edited.pptx")); var deck = new XMLSlideShow(in)) {
            assertEquals(2, deck.getSlides().size());
        }
    }

    @Test
    void rejectsInPlaceEditAndOverwriteForAllFormats() throws Exception {
        spec("base.json", "{\"sheets\":[{\"name\":\"S\",\"rows\":[[\"A\",1]]}]}");
        created("create_xlsx", "same.xlsx", "base.json");
        spec("edit.json", "{\"cells\":[{\"sheet\":\"S\",\"row\":0,\"col\":0,\"value\":2}]}");
        assertTrue(run(
            Map.of("action", "edit_xlsx", "path", "same.xlsx", "spec_path", "edit.json", "source_path", "same.xlsx"))
            .text().startsWith("ERROR"));

        spec("doc.json", "{\"blocks\":[{\"type\":\"paragraph\",\"text\":\"X\"}]}");
        created("create_docx", "exists.docx", "doc.json");
        assertTrue(run(Map.of("action", "create_docx", "path", "exists.docx", "spec_path", "doc.json")).text()
            .startsWith("ERROR"));
        spec("pdf.json", "{\"blocks\":[{\"type\":\"paragraph\",\"text\":\"X\"}]}");
        created("create_pdf", "exists.pdf", "pdf.json");
        assertTrue(run(Map.of("action", "create_pdf", "path", "exists.pdf", "spec_path", "pdf.json")).text()
            .startsWith("ERROR"));
    }

    @Test
    void readFileExtractsOfficeTextInsteadOfReportingBinary() throws Exception {
        spec("doc.json", "{\"blocks\":[{\"type\":\"paragraph\",\"text\":\"Readback works\"}]}");
        created("create_docx", "readback.docx", "doc.json");
        spec("base.json", "{\"sheets\":[{\"name\":\"S\",\"rows\":[[\"Cell value\"]]}]}");
        created("create_xlsx", "readback.xlsx", "base.json");
        var readFile = new ReadFileTool(new PathValidator(root));
        String docx = readFile.executeResult(Map.of("path", "readback.docx")).text();
        assertTrue(docx.contains("Readback works"), docx);
        assertFalse(docx.startsWith("ERROR"));
        String xlsx = readFile.executeResult(Map.of("path", "readback.xlsx")).text();
        assertTrue(xlsx.contains("Cell value"), xlsx);
        assertFalse(xlsx.startsWith("ERROR"));
    }

    @Test
    void rejectsUnknownActionDamagedOfficeAndBadSpec() throws Exception {
        assertTrue(run(Map.of("action", "nope", "path", "x.docx")).text().startsWith("ERROR"));
        Files.writeString(root.resolve("bad.docx"), "not a zip");
        assertTrue(tool().execute(Map.of("action", "inspect", "path", "bad.docx")).startsWith("ERROR"));
        Files.writeString(root.resolve("bad.pdf"), "not a pdf");
        assertTrue(tool().execute(Map.of("action", "inspect", "path", "bad.pdf")).startsWith("ERROR"));
        spec("bad-spec.json", "{\"blocks\":[{\"type\":\"unknown\",\"text\":\"x\"}]}");
        var result = run(Map.of("action", "create_docx", "path", "bad-spec-output.docx", "spec_path", "bad-spec.json"));
        assertTrue(result.text().startsWith("ERROR"), result.text());
        assertFalse(Files.exists(root.resolve("bad-spec-output.docx")));
        assertEquals("not a zip", Files.readString(root.resolve("bad.docx")));
    }
}
