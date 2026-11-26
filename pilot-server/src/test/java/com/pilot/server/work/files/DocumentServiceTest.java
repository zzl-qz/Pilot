package com.pilot.server.work.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.tool.office.DocumentRenderer;
import com.pilot.server.store.AutomationStore;
import com.pilot.server.store.PilotDataDir;
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
    void rejectsForeignSessionPdfMergeAndSymbolicLinkArtifactDownload() throws Exception {
        var service = service();
        var document = service.create(SESSION, "Report", "blocks", SPEC, "human");
        var otherDocument = service.create("ses_other_0002", "Other", "blocks", SPEC, "human");
        var artifact =
            service.render(SESSION, document.id(), JSON.readTree("{\"formats\":[\"pdf\"]}")).artifacts().getFirst();
        assertThrows(IllegalArgumentException.class, () -> service.render("ses_other_0002", document.id(),
            JSON.readTree("{\"formats\":[\"pdf\"],\"pdf_operations\":{\"merge\":[\"" + artifact.filename() + "\"]}}")));
        // 另一会话用自己的文档尝试通过相对路径或 studio 子路径合并本会话的 PDF 产物，必须拒绝
        for (String foreignRef : new String[] {document.id() + "/" + artifact.filename(),
            "../" + document.id() + "/" + artifact.filename()}) {
            assertThrows(IllegalStateException.class,
                () -> service.render("ses_other_0002", otherDocument.id(),
                    JSON.readTree("{\"formats\":[\"pdf\"],\"pdf_operations\":{\"merge\":[\"" + foreignRef + "\"]}}")),
                foreignRef);
        }

        Path target = temp.resolve("outside.pdf");
        Files.write(target, Files.readAllBytes(service.download(SESSION, document.id(), artifact.id()).file()));
        Path artifactPath = temp.resolve("studio").resolve(document.id()).resolve(artifact.filename());
        Files.delete(artifactPath);
        try {
            Files.createSymbolicLink(artifactPath, target);
        } catch (UnsupportedOperationException | FileSystemException e) {
            Assumptions.abort("当前平台未提供创建符号链接权限");
        }
        assertThrows(IllegalArgumentException.class, () -> service.download(SESSION, document.id(), artifact.id()));
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
    void invalidFormatInBatchIsRejectedBeforeCreatingAnything() throws Exception {
        var service = service();
        var document = service.create(SESSION, "Report", "blocks", SPEC, "human");

        assertThrows(IllegalArgumentException.class,
            () -> service.render(SESSION, document.id(), JSON.readTree("{\"formats\":[\"docx\",\"unsupported\"]}")));
        assertTrue(service.artifacts(SESSION, document.id()).isEmpty(), "整批校验失败不能留下产物记录");
        Path directory = temp.resolve("studio").resolve(document.id());
        if (Files.isDirectory(directory)) {
            try (var files = Files.list(directory)) {
                assertEquals(0, files.count(), "磁盘上也不能留下产物文件：" + directory);
            }
        }

        // 正常重试只生成一份
        var retried = service.render(SESSION, document.id(), JSON.readTree("{\"formats\":[\"docx\"]}"));
        assertEquals(1, retried.artifacts().size());
        assertEquals(1, service.artifacts(SESSION, document.id()).size());
    }

    @Test
    void failedRenderInBatchRollsBackEarlierArtifacts() throws Exception {
        var service = service();
        var document = service.create(SESSION, "Report", "blocks", SPEC, "human");

        // docx 先成功，随后 pdf 的分页操作越界导致整批失败
        assertThrows(IllegalStateException.class, () -> service.render(SESSION, document.id(),
            JSON.readTree("{\"formats\":[\"docx\",\"pdf\"],\"pdf_operations\":{\"keep_pages\":[99]}}")));
        assertTrue(service.artifacts(SESSION, document.id()).isEmpty(), "失败后不能残留已生成的产物");
        Path directory = temp.resolve("studio").resolve(document.id());
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count(), "失败后磁盘也必须干净：" + directory);
        }

        var retried = service.render(SESSION, document.id(), JSON.readTree("{\"formats\":[\"docx\",\"pdf\"]}"));
        assertEquals(2, retried.artifacts().size());
        assertEquals(2, service.artifacts(SESSION, document.id()).size(), "重试不会重复登记同版本产物");
    }

    @Test
    void duplicateFormatsAreRejected() {
        var service = service();
        var document = service.create(SESSION, "Report", "blocks", SPEC, "human");
        assertThrows(IllegalArgumentException.class,
            () -> service.render(SESSION, document.id(), JSON.readTree("{\"formats\":[\"docx\",\"docx\"]}")));
        assertTrue(service.artifacts(SESSION, document.id()).isEmpty());
    }

    @Test
    void deletesOnlyTargetSessionDocumentsVersionsArtifactsAndExports() throws Exception {
        var service = service();
        var keep = service.create("ses_keep", "Keep", "blocks", SPEC, "human");
        var drop = service.create("ses_drop", "Drop", "blocks", SPEC, "human");
        service.update("ses_drop", drop.id(), 1, "Drop v2", SPEC.replace("Conclusion", "Changed"), "human");
        var keepArtifact =
            service.render("ses_keep", keep.id(), JSON.readTree("{\"formats\":[\"docx\"]}")).artifacts().getFirst();
        var dropArtifact =
            service.render("ses_drop", drop.id(), JSON.readTree("{\"formats\":[\"docx\"]}")).artifacts().getFirst();
        Path keepDir = temp.resolve("studio").resolve(keep.id());
        Path dropDir = temp.resolve("studio").resolve(drop.id());
        assertTrue(Files.isRegularFile(keepDir.resolve(keepArtifact.filename())));
        assertTrue(Files.isRegularFile(dropDir.resolve(dropArtifact.filename())));

        service.deleteBySession("ses_drop");

        assertTrue(service.list("ses_drop").isEmpty());
        assertEquals(1, service.list("ses_keep").size());
        assertFalse(Files.exists(dropDir), "被删会话的导出目录必须清理");
        assertTrue(Files.isRegularFile(keepDir.resolve(keepArtifact.filename())), "其他会话导出必须保留");
        assertTrue(Files.isRegularFile(service.download("ses_keep", keep.id(), keepArtifact.id()).file()));
        // 重试必须幂等：记录和目录都清掉后再调一次不报错
        service.deleteBySession("ses_drop");
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
