package com.pilot.server.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.session.SessionService;
import com.pilot.server.config.PilotDataDir;
import com.pilot.server.store.AutomationStore;
import com.pilot.server.store.SqliteStore;
import com.pilot.server.workspace.WorkspaceToolFactory;

import tools.jackson.databind.ObjectMapper;

/** 文件工作台：真实文件是唯一源，元数据只是索引与审阅辅助。 */
class FileWorkbenchServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    private Path workspace;
    private Path pdf;
    private String session;
    private WorkProductService products;
    private FileWorkbenchService service;

    @BeforeEach
    void setUp() throws Exception {
        workspace = Files.createDirectories(temp.resolve("workspace"));
        Files.createDirectories(workspace.resolve("outputs"));
        pdf = workspace.resolve("outputs").resolve("report.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.addPage(new PDPage());
            document.save(pdf.toFile());
        }
        Files.createDirectories(workspace.resolve(".pilot"));
        Files.writeString(workspace.resolve(".pilot").resolve("hidden.docx"), "not a real docx");

        WorkspaceToolFactory workspaces = mock(WorkspaceToolFactory.class);
        when(workspaces.resolve(anyString())).thenReturn(workspace);
        products = mock(WorkProductService.class);
        SessionService sessions = new SessionService();
        session = sessions.createSession("文件工作台测试", workspace.toString()).id();
        service = new FileWorkbenchService(new AutomationStore((SqliteStore)null), JSON, workspaces, sessions,
            new PilotDataDir(temp.resolve("data").toString()), products);
    }

    @Test
    void indexesRealWorkspaceFilesAndIgnoresRuntimeDirectories() throws Exception {
        var files = service.list(session);
        assertEquals(1, files.size(), "隐藏目录和无关文件不应被当成产物");
        assertEquals("outputs/report.pdf", files.get(0).path());
        assertEquals("pdf", files.get(0).format());
        assertFalse(files.get(0).sha256().isBlank());
        assertEquals(files.get(0).id(), service.asset(session, files.get(0).id()).id(), "文件 id 由路径稳定派生");
    }

    @Test
    void rendersRealPdfPreviewPages() throws Exception {
        String id = service.list(session).get(0).id();
        var preview = service.preview(session, id);
        assertEquals(2, preview.pages());
        assertTrue(preview.notice().contains("原始版式"));
        byte[] image = service.previewPage(session, id, 1).content();
        assertTrue(image.length > 100);
        assertEquals((byte)0x89, image[0], "应当是 PNG");
    }

    @Test
    void annotationsProduceAMinimalScopeChangeRequest() throws Exception {
        String id = service.list(session).get(0).id();
        var annotation = service.annotate(session, id, 2, "第二段", "把标题改成季度报告");
        assertTrue(annotation.page() == 2);
        String request = service.changeRequest(session, id, annotation.id());
        assertTrue(request.contains("outputs/report.pdf"));
        assertTrue(request.contains("把标题改成季度报告"));
        assertTrue(request.contains("第 2 页"));
        assertTrue(request.contains("保持最小改动范围"));
        assertEquals(1, service.annotations(session, id).size());
    }

    @Test
    void detectsExternalEditAndRevertsWithoutLosingHistory() throws Exception {
        String id = service.list(session).get(0).id();
        byte[] original = Files.readAllBytes(pdf);
        service.snapshot(session, id, "初版");
        assertFalse(service.asset(session, id).changedExternally());

        Files.writeString(pdf, "externally edited", StandardCharsets.UTF_8);
        assertTrue(service.asset(session, id).changedExternally(), "本机应用保存后应能感知");
        assertNotEquals(service.asset(session, id).sha256(), service.versions(session, id).get(0).sha256());

        service.revert(session, id, 1);
        assertFalse(service.asset(session, id).changedExternally());
        assertEquals(original.length, Files.size(pdf));
        assertEquals(3, service.versions(session, id).size(), "回退前的状态也要另存为快照");
    }

    @Test
    void publishesTheWorkspacePathSoArtifactsAreReachable() throws Exception {
        String id = service.list(session).get(0).id();
        service.publish(session, id, "季度报告", "已核对");
        verify(products).publish(eq(session), isNull(), eq("outputs/report.pdf"), eq("季度报告"), eq("已核对"), any());
    }

    @Test
    void pdfOperationsNeverOverwriteTheSource() throws Exception {
        String id = service.list(session).get(0).id();
        var created = service.pdfOperations(session, id, JSON.readTree("{\"page_numbers\":true}"));
        assertNotEquals(id, created.id());
        assertTrue(created.filename().endsWith(".pdf"));
        assertTrue(Files.exists(pdf), "源文件必须保留");
        assertFalse(Files.readAllBytes(pdf).length == 0);
        assertEquals(2, service.list(session).size(), "新产物会立刻出现在工作台里");
    }

    @Test
    void sessionCleanupKeepsFilesButDropsMetadata() throws Exception {
        String id = service.list(session).get(0).id();
        service.annotate(session, id, 1, "", "请修改");
        service.snapshot(session, id, "手动快照");
        assertFalse(service.annotations(session, id).isEmpty());

        service.deleteBySession(session);
        assertTrue(service.annotations(session, id).isEmpty());
        assertTrue(service.versions(session, id).isEmpty());
        assertTrue(Files.exists(pdf), "工作区里的真实文件属于用户，不能被级联删除");
    }
}
