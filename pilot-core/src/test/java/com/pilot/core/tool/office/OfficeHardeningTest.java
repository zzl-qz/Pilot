package com.pilot.core.tool.office;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.ObjectMapper;

class OfficeHardeningTest {
    @TempDir
    Path tempDir;

    @Test
    void limitsHugePdfPageAndKeepsNormalPageDpi() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage huge = new PDPage(new PDRectangle(100_000, 100_000));
            huge.setMediaBox(new PDRectangle(100_000, 100_000));
            huge.setCropBox(new PDRectangle(100_000, 100_000));
            document.addPage(huge);
            assertTrue(huge.getMediaBox().getWidth() > 90_000);
            int rotation = Math.floorMod(huge.getRotation(), 360);
            assertTrue(rotation == 0);
            float dpi = OfficeSupport.boundedPdfDpi(huge, 144);
            assertTrue(dpi < 6.0f);
            var image = OfficeSupport.renderPdfPage(new PDFRenderer(document), huge, 0, 144);
            assertTrue(image.getWidth() <= 8192);
            assertTrue(image.getHeight() <= 8192);
            assertTrue((long)image.getWidth() * image.getHeight() <= 32_000_000L);

            PDPage normal = new PDPage(PDRectangle.A4);
            assertEquals(144.0f, OfficeSupport.boundedPdfDpi(normal, 144));
        }
    }

    @Test
    void protectsCyclicFormulaCellAndHardensZipLimits() throws IOException {
        OfficeSupport.hardenZip();
        assertTrue(ZipSecureFile.getMinInflateRatio() >= 0.01);
        assertTrue(ZipSecureFile.getMaxEntrySize() <= 50L * 1024 * 1024);
        assertTrue(ZipSecureFile.getMaxTextSize() <= 10L * 1024 * 1024);

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Cycle");
            var first = sheet.createRow(0).createCell(0);
            var second = sheet.getRow(0).createCell(1);
            first.setCellFormula("B1+1");
            second.setCellFormula("A1+1");
            var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            String fallback = OfficeSupport.safeCellText(first, new DataFormatter(), evaluator);
            assertEquals("=B1+1", fallback);
            assertNotEquals("", fallback);
        }
    }

    @Test
    void workbookRendererPreservesFormulasAndExtractorSurvivesCyclicWorkbook() throws Exception {
        Path file = tempDir.resolve("formulas.xlsx");
        var spec = new ObjectMapper().readTree("""
            {
              "sheets": [{
                "name": "Summary",
                "rows": [
                  ["A", "B", "Sum"],
                  [10, 20, {"formula": "A2+B2"}]
                ]
              }]
            }
            """);
        OfficeWorkbookRenderer.create(spec, file);
        try (InputStream in = Files.newInputStream(file); XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            var row = workbook.getSheet("Summary").getRow(1);
            var sumCell = row.getCell(2);
            assertEquals(CellType.FORMULA, sumCell.getCellType(), "正常公式校验后必须保留 FORMULA 类型");
            assertEquals("A2+B2", sumCell.getCellFormula());
            assertEquals(30.0, sumCell.getNumericCellValue());
        }

        // 外部恶意循环公式工作簿在文本抽取与预览链路中应单格降级，不得崩溃
        Path cyclic = tempDir.resolve("cyclic.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); var out = Files.newOutputStream(cyclic)) {
            var sheet = workbook.createSheet("Cycle");
            var row = sheet.createRow(0);
            row.createCell(0).setCellFormula("B1+1");
            row.createCell(1).setCellFormula("A1+1");
            row.createCell(2).setCellValue("safe-cell");
            workbook.write(out);
        }
        String extracted = OfficeSupport.extractText(cyclic);
        assertTrue(extracted.contains("=B1+1"), extracted);
        assertTrue(extracted.contains("safe-cell"), extracted);
    }
}
