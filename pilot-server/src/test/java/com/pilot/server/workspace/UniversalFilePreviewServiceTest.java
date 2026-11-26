package com.pilot.server.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

import com.pilot.core.util.HostOs;
import com.pilot.core.workspace.WorkspaceViolationException;

class UniversalFilePreviewServiceTest {
    @TempDir
    Path workspace;

    @Test
    void contentDispositionHasAsciiFallbackUtf8NameAndRejectsHeaderInjection() {
        String header = UniversalFilePreviewService.contentDisposition("报告\"\r\n.txt");
        assertTrue(header.startsWith("attachment; filename=\""));
        assertTrue(header.contains("filename*=UTF-8''"));
        assertFalse(header.contains("\r"));
        assertFalse(header.contains("\n"));
        assertTrue(header.contains("%E6%8A%A5%E5%91%8A"));

        var failed = UniversalFilePreviewService
            .runNativeLauncher(HostOs.isWindows() ? List.of("cmd.exe", "/c", "echo no-handler 1>&2 & exit 3")
                : List.of("/bin/sh", "-c", "echo no-handler >&2; exit 3"), "demo.pdf", false);
        assertFalse(failed.ok());
        assertTrue(failed.message().contains("退出码 3"), failed.message());
        assertTrue(failed.message().contains("no-handler"), failed.message());
    }

    @Test
    void parsesDocxXlsxPptxAndArchiveNativelyWithoutExternalTools() throws Exception {
        // 1. DOCX
        Path docx = workspace.resolve("report.docx");
        try (XWPFDocument doc = new XWPFDocument(); OutputStream out = Files.newOutputStream(docx)) {
            XWPFParagraph heading = doc.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("季度复盘");
            XWPFParagraph body = doc.createParagraph();
            body.createRun().setText("核心指标稳步提升。");
            XWPFTable table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("指标");
            table.getRow(0).getCell(1).setText("数值");
            table.getRow(1).getCell(0).setText("命中率");
            table.getRow(1).getCell(1).setText("98%");
            doc.write(out);
        }
        SessionFilesController.FileResponse docxRes = SessionFilesController.fileWorkspace(workspace, "report.docx");
        assertEquals("docx", docxRes.kind());
        assertFalse(docxRes.binary());
        assertNotNull(docxRes.structured());
        assertEquals(3, docxRes.structured().blocks().size());
        assertEquals(1, docxRes.structured().blocks().get(0).level());
        assertEquals("季度复盘", docxRes.structured().blocks().get(0).text());
        assertEquals("table", docxRes.structured().blocks().get(2).type());

        // 2. XLSX（含多 Sheet 与公式求值）
        Path xlsx = workspace.resolve("budget.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(xlsx)) {
            Sheet s1 = wb.createSheet("汇总");
            Row r0 = s1.createRow(0);
            r0.createCell(0).setCellValue("项目");
            r0.createCell(1).setCellValue("金额");
            Row r1 = s1.createRow(1);
            r1.createCell(0).setCellValue("总计");
            r1.createCell(1).setCellFormula("10+25");
            wb.createSheet("明细").createRow(0).createCell(0).setCellValue("条目A");
            wb.write(out);
        }
        SessionFilesController.FileResponse xlsxRes = SessionFilesController.fileWorkspace(workspace, "budget.xlsx");
        assertEquals("xlsx", xlsxRes.kind());
        assertNotNull(xlsxRes.structured());
        assertEquals(2, xlsxRes.structured().sheets().size());
        assertEquals("汇总", xlsxRes.structured().sheets().get(0).name());
        assertEquals("35", xlsxRes.structured().sheets().get(0).rows().get(1).get(1));

        // 3. PPTX
        Path pptx = workspace.resolve("deck.pptx");
        try (XMLSlideShow ppt = new XMLSlideShow(); OutputStream out = Files.newOutputStream(pptx)) {
            XSLFSlide slide = ppt.createSlide();
            XSLFTextBox box = slide.createTextBox();
            box.setText("架构升级路线");
            ppt.write(out);
        }
        SessionFilesController.FileResponse pptxRes = SessionFilesController.fileWorkspace(workspace, "deck.pptx");
        assertEquals("pptx", pptxRes.kind());
        assertNotNull(pptxRes.structured());
        assertEquals(1, pptxRes.structured().slides().size());
        assertTrue(pptxRes.structured().slides().get(0).paragraphs().contains("架构升级路线"));

        // 4. ZIP
        Path zip = workspace.resolve("bundle.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("docs/readme.txt"));
            zos.write("hello zip".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        SessionFilesController.FileResponse zipRes = SessionFilesController.fileWorkspace(workspace, "bundle.zip");
        assertEquals("archive", zipRes.kind());
        assertNotNull(zipRes.structured());
        assertEquals(1, zipRes.structured().entries().size());
        assertEquals("docs/readme.txt", zipRes.structured().entries().get(0).path());
    }

    @Test
    void streamsRawMediaSafelyAndBlocksWorkspaceTraversal() throws Exception {
        Path png = workspace.resolve("shot.png");
        Files.write(png, new byte[] {(byte)0x89, 0x50, 0x4E, 0x47});
        Path svg = workspace.resolve("icon.svg");
        Files.writeString(svg, "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>");

        SessionFilesController.FileResponse pngMeta = SessionFilesController.fileWorkspace(workspace, "shot.png");
        assertEquals("image", pngMeta.kind());
        assertEquals("image/png", pngMeta.mimeType());

        ResponseEntity<Resource> rawPng = SessionFilesController.rawWorkspace(workspace, "shot.png", false);
        assertEquals("image/png", rawPng.getHeaders().getContentType().toString());
        assertEquals("nosniff", rawPng.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals("no-store", rawPng.getHeaders().getFirst("Cache-Control"));
        assertTrue(rawPng.getHeaders().getFirst("Content-Security-Policy").contains("sandbox"));

        ResponseEntity<Resource> rawSvg = SessionFilesController.rawWorkspace(workspace, "icon.svg", false);
        assertEquals("application/octet-stream", rawSvg.getHeaders().getContentType().toString());
        assertTrue(rawSvg.getHeaders().getFirst("Content-Disposition").contains("attachment"));
        assertTrue(rawSvg.getHeaders().getFirst("Content-Security-Policy").contains("sandbox"));

        for (String name : List.of("evil.xhtml", "doc.xml", "page.html", "报告.html")) {
            Path target = workspace.resolve(name);
            Files.writeString(target, "<html xmlns=\"http://www.w3.org/1999/xhtml\"><script>alert(1)</script></html>");
            ResponseEntity<Resource> raw = SessionFilesController.rawWorkspace(workspace, name, false);
            assertEquals("application/octet-stream", raw.getHeaders().getContentType().toString(), name);
            assertEquals("nosniff", raw.getHeaders().getFirst("X-Content-Type-Options"), name);
            assertEquals("no-store", raw.getHeaders().getFirst("Cache-Control"), name);
            assertTrue(raw.getHeaders().getFirst("Content-Security-Policy").contains("sandbox"), name);
            assertTrue(raw.getHeaders().getFirst("Content-Disposition").contains("attachment"), name);
        }

        ResponseEntity<Resource> download = SessionFilesController.rawWorkspace(workspace, "shot.png", true);
        assertTrue(download.getHeaders().getFirst("Content-Disposition").contains("attachment"));

        assertThrows(WorkspaceViolationException.class,
            () -> SessionFilesController.rawWorkspace(workspace, "../outside.txt", false));
    }

    @Test
    void blocksExecutableAndScriptNativeOpenIncludingTrailingDots() throws Exception {
        for (String name : List.of("setup.exe", "payload.exe.", "run.sh", "script.ps1", "app.desktop")) {
            Path file = workspace.resolve(name);
            Files.writeString(file, "payload");
            UniversalFilePreviewService.OpenResult result = UniversalFilePreviewService.openNative(file, "open");
            assertFalse(result.ok(), name);
            assertTrue(result.message().contains("安全原因"), name + " -> " + result.message());
        }
    }

    @Test
    void buildsCrossPlatformNativeOpenAndRevealCommands() {
        Path sample = workspace.resolve("demo.pdf");
        Path comma = workspace.resolve("a,b.pdf");
        assertEquals(List.of("open", "--", sample.toAbsolutePath().normalize().toString()),
            UniversalFilePreviewService.nativeCommand(sample, false, "Mac OS X"));
        assertEquals(List.of("open", "-R", "--", sample.toAbsolutePath().normalize().toString()),
            UniversalFilePreviewService.nativeCommand(sample, true, "Mac OS X"));
        assertEquals(List.of("explorer.exe", "/select," + sample.toAbsolutePath().normalize()),
            UniversalFilePreviewService.nativeCommand(sample, true, "Windows 11"));
        assertEquals(List.of("explorer.exe", comma.toAbsolutePath().normalize().getParent().toString()),
            UniversalFilePreviewService.nativeCommand(comma, true, "Windows 11"));
        assertEquals(List.of(), UniversalFilePreviewService.nativeCommand(sample, false, "Windows 11"));
        assertEquals(List.of("xdg-open", sample.toAbsolutePath().normalize().getParent().toString()),
            UniversalFilePreviewService.nativeCommand(sample, true, "Linux"));
        assertEquals(List.of("xdg-open", sample.toAbsolutePath().normalize().toString()),
            UniversalFilePreviewService.nativeCommand(sample, false, "Linux"));
    }
}
