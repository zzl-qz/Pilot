package com.pilot.server.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.tool.DocumentRenderer;
import com.pilot.server.config.PilotDataDir;
import com.pilot.server.store.AutomationStore;
import com.pilot.server.store.SqliteStore;

import tools.jackson.databind.ObjectMapper;

class DocumentServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "ses_test_0001";
    private static final String SPEC =
        """
            {"title":"Quarterly Report","blocks":[{"type":"paragraph","text":"Conclusion"},{"type":"page_break"},{"type":"paragraph","text":"Page two"}]}
            """;

    @TempDir
    Path temp;

    private DocumentService service() {
        return new DocumentService(new AutomationStore((SqliteStore)null), new PilotDataDir(temp.toString()), null);
    }

    @Test
    void createsUpdatesVersionsAndRollsBackWithoutDeletingHistory() {
        var service = service();
        var created = service.create(SESSION, "季度报告", "blocks", SPEC, "human");
        assertEquals(1, created.version());
        assertEquals(SESSION, created.sessionId());
        var updated = service.update(SESSION, created.id(), 1, "季度报告", SPEC.replace("Conclusion", "Revised"), "human");
        assertEquals(2, updated.version());
        assertEquals(2, service.versions(SESSION, created.id()).size());
        var rolledBack = service.rollback(SESSION, created.id(), 2, 1, "human");
        assertEquals(3, rolledBack.version());
        assertTrue(rolledBack.spec().contains("Conclusion"));
        assertEquals(3, service.versions(SESSION, created.id()).size());
        assertEquals("季度报告", rolledBack.title());
    }

    @Test
    void rendersDocxAndPdfDirectlyWithoutModel() throws Exception {
        var service = service();
        var document = service.create(SESSION, "Report", "blocks", SPEC, "human");
        var result = service.render(SESSION, document.id(), JSON.readTree("{\"formats\":[\"docx\",\"pdf\"]}"));
        assertEquals(2, result.artifacts().size());
        for (var artifact : result.artifacts()) {
            var download = service.download(SESSION, document.id(), artifact.id());
            assertTrue(Files.isRegularFile(download.file()));
            assertTrue(Files.size(download.file()) > 0);
            assertNotNull(artifact.sha256());
            assertTrue(artifact.verification().contains("读回"));
        }
        assertTrue(result.artifacts().stream().anyMatch(item -> "docx".equals(item.format())));
        assertTrue(result.artifacts().stream().anyMatch(item -> "pdf".equals(item.format())));
        assertEquals(2, service.artifacts(SESSION, document.id()).size());
    }

    @Test
    void appliesPdfPageOperationsDuringExport() throws Exception {
        var service = service();
        var document = service.create(SESSION, "Report", "blocks", SPEC, "human");
        var result = service.render(SESSION, document.id(), JSON.readTree("""
            {"formats":["pdf"],"pdf_operations":{"keep_pages":[1],"page_numbers":true,"watermark":"DRAFT"}}
            """));
        assertEquals(1, result.artifacts().size());
        var artifact = result.artifacts().get(0);
        assertTrue(artifact.verification().contains("加页码"));
        assertTrue(artifact.verification().contains("加水印"));
        var file = service.download(SESSION, document.id(), artifact.id()).file();
        assertTrue(DocumentRenderer.inspect(file).contains("Conclusion"));
        try (var pdf = Loader.loadPDF(file.toFile())) {
            assertEquals(1, pdf.getNumberOfPages());
        }
    }

    @Test
    void rejectsStaleVersionBadSpecAndForeignIds() {
        var service = service();
        var document = service.create(SESSION, "Report", "blocks", SPEC, "human");
        assertThrows(IllegalArgumentException.class,
            () -> service.update(SESSION, document.id(), 99, "X", SPEC, "human"));
        assertThrows(IllegalArgumentException.class,
            () -> service.create(SESSION, "Bad", "blocks", "not json", "human"));
        assertThrows(IllegalArgumentException.class, () -> service.get("ses_other_0002", document.id()));
        assertThrows(IllegalArgumentException.class, () -> service.get(SESSION, "../escape"));
        assertThrows(IllegalArgumentException.class,
            () -> service.download(SESSION, document.id(), "sta_../../secret"));
    }

    @Test
    void keepsIndependentDocumentsAndSeparatesSpecMode() {
        var service = service();
        var first = service.create(SESSION, "A", "blocks", SPEC, "human");
        var second = service.create(SESSION, "B", "spec", "{\"sheets\":[]}", "ai");
        assertEquals(2, service.list(SESSION).size());
        assertEquals("spec", service.get(SESSION, second.id()).kind());
        assertFalse(first.id().equals(second.id()));
        assertEquals(0, service.list("ses_other_0009").size());
        assertTrue(service.artifacts(SESSION, first.id()).isEmpty());
    }

    @Test
    void mergesPreviouslyExportedPdfByFilename() throws Exception {
        var service = service();
        var document = service.create(SESSION, "Report", "blocks", SPEC, "human");
        var first = service.render(SESSION, document.id(), JSON.readTree("{\"formats\":[\"pdf\"]}"));
        String filename = first.artifacts().get(0).filename();
        var merged = service.render(SESSION, document.id(),
            JSON.readTree("{\"formats\":[\"pdf\"],\"pdf_operations\":{\"merge\":[\"" + filename + "\"]}}"));
        var artifact = merged.artifacts().get(0);
        assertTrue(artifact.verification().contains("合并 " + filename));
        try (var pdf = Loader.loadPDF(service.download(SESSION, document.id(), artifact.id()).file().toFile())) {
            assertEquals(4, pdf.getNumberOfPages());
        }
    }
}
