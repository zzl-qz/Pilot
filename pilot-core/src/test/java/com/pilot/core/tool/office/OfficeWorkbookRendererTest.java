package com.pilot.core.tool.office;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.ObjectMapper;

class OfficeWorkbookRendererTest {
    @TempDir
    Path directory;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void createsAndEditsWorkbookWithoutToolDependencies() throws Exception {
        Path source = directory.resolve("source.xlsx");
        Path target = directory.resolve("edited.xlsx");
        OfficeWorkbookRenderer.create(json.readTree("""
            {"sheets":[{"name":"Sales","rows":[["Amount",10],["Total",{"formula":"SUM(B1:B1)"}]]}]}
            """), source);
        OfficeWorkbookRenderer.edit(source, target, json.readTree(
            """
                {"cells":[{"sheet":"Sales","row":0,"col":0,"value":"Revenue"}],"append_rows":[{"sheet":"Sales","rows":[["Next",20]]}]}
                """));

        try (InputStream in = Files.newInputStream(target); var workbook = new XSSFWorkbook(in)) {
            var sheet = workbook.getSheet("Sales");
            assertEquals("Revenue", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("Next", sheet.getRow(2).getCell(0).getStringCellValue());
            assertEquals(10.0, sheet.getRow(1).getCell(1).getNumericCellValue());
        }
    }

    @Test
    void rejectsFormulaErrorsAndExternalFormulaReferences() throws Exception {
        Path invalidFormula = directory.resolve("invalid-formula.xlsx");
        assertThrows(IllegalArgumentException.class, () -> OfficeWorkbookRenderer.create(json.readTree("""
            {"sheets":[{"name":"S","rows":[[{"formula":"1/0"}]]}]}
            """), invalidFormula));

        Path externalFormula = directory.resolve("external-formula.xlsx");
        assertThrows(IllegalArgumentException.class, () -> OfficeWorkbookRenderer.create(json.readTree("""
            {"sheets":[{"name":"S","rows":[[{"formula":"WEBSERVICE(\\"https://example.invalid\\")"}]]}]}
            """), externalFormula));
    }
}
