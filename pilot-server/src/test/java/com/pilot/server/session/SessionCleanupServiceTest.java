package com.pilot.server.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import com.pilot.core.browser.BrowserBackend;
import com.pilot.core.browser.BrowserEvidenceRecord;
import com.pilot.core.browser.BrowserHandoffBroker;
import com.pilot.core.session.ConversationRepository;
import com.pilot.core.session.PlanRepository;
import com.pilot.core.session.RunCheckpointRepository;
import com.pilot.core.session.RunRepository;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.core.tool.command.CommandTaskManager;
import com.pilot.server.attachment.AttachmentReferences;
import com.pilot.server.attachment.AttachmentService;
import com.pilot.server.browser.BrowserEvidenceService;
import com.pilot.server.multi.SandboxStore;
import com.pilot.server.run.RunStarter;
import com.pilot.server.run.TaskControlService;
import com.pilot.server.session.notes.SessionNotes;
import com.pilot.server.store.AutomationStore;
import com.pilot.server.store.PilotDataDir;
import com.pilot.server.store.SqliteStore;
import com.pilot.server.store.StorageHealthService;
import com.pilot.server.store.ToolCallJournalFactory;
import com.pilot.server.validation.ValidationLedger;
import com.pilot.server.work.files.DocumentService;
import com.pilot.server.work.files.FileWorkbenchService;
import com.pilot.server.work.products.SkillDraftService;
import com.pilot.server.work.products.WorkProductService;

import tools.jackson.databind.ObjectMapper;

/** 会话删除必须级联文档工作台，且失败时保留会话以便原样重试。 */
class SessionCleanupServiceTest {
    private static final String SPEC = """
        {"title":"Cleanup","blocks":[{"type":"paragraph","text":"body"}]}
        """;

    @TempDir
    Path temp;

    @Test
    void deletesSessionDocumentsVersionsExportsAndKeepsOtherFiles() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        SessionService sessions = new SessionService();
        Session session = sessions.createSession("cleanup", workspace.toString());
        PilotDataDir dataDir = new PilotDataDir(temp.toString());
        DocumentService documents =
            new DocumentService(new AutomationStore((SqliteStore)null), dataDir, mock(WorkProductService.class));
        DocumentService.Document document = documents.create(session.id(), "Doc", "blocks", SPEC, "human");
        documents.update(session.id(), document.id(), 1, "Doc v2", SPEC.replace("body", "changed"), "human");
        Path export = temp.resolve("studio").resolve(document.id());
        Files.createDirectories(export);
        Files.writeString(export.resolve("artifact.docx"), "exported bytes");
        FileWorkbenchService fileWorkbench = mock(FileWorkbenchService.class);

        SessionCleanupService cleanup = cleanup(sessions, documents, dataDir, fileWorkbench);

        assertTrue(cleanup.delete(session.id()));
        assertTrue(documents.list(session.id()).isEmpty());
        assertFalse(Files.exists(export), "导出目录必须随会话清理");
        verify(fileWorkbench).deleteBySession(session.id());
        assertTrue(sessions.find(session.id()).isEmpty());
    }

    @Test
    void keepsSessionAndReturnsRetryableFailureWhenDocumentCleanupFails() {
        SessionService sessions = mock(SessionService.class);
        when(sessions.list()).thenReturn(List.of());
        when(sessions.delete("ses_retry")).thenReturn(true);
        DocumentService documents = mock(DocumentService.class);
        doThrow(new IllegalStateException("磁盘忙")).when(documents).deleteBySession("ses_retry");
        PilotDataDir dataDir = new PilotDataDir(temp.toString());

        SessionCleanupService cleanup = cleanup(sessions, documents, dataDir, mock(FileWorkbenchService.class));

        SessionCleanupService.CleanupException failure =
            assertThrows(SessionCleanupService.CleanupException.class, () -> cleanup.delete("ses_retry"));
        assertTrue(failure.retryable());
        assertTrue(failure.getMessage().contains("可重试"), failure.getMessage());
        verify(sessions, never()).delete(anyString());
    }

    @Test
    void deletesBrowserTreeCancelsHandoffsAndClearsEvidence() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace-b"));
        SessionService sessions = new SessionService();
        Session session = sessions.createSession("cleanup-browser", workspace.toString());
        PilotDataDir dataDir = new PilotDataDir(temp.toString());
        DocumentService documents =
            new DocumentService(new AutomationStore((SqliteStore)null), dataDir, mock(WorkProductService.class));
        BrowserBackend backend = mock(BrowserBackend.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<BrowserBackend> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(backend);
        RunStarter runs = mock(RunStarter.class);
        when(runs.activeRun(anyString())).thenReturn(Optional.empty());
        AttachmentReferences references = mock(AttachmentReferences.class);
        when(references.retainedOwners()).thenReturn(Set.of());
        SessionCleanupService service = new SessionCleanupService(sessions, mock(ConversationRepository.class),
            mock(PlanRepository.class), mock(RunRepository.class), mock(SandboxStore.class), runs,
            mock(TaskControlService.class), mock(CommandTaskManager.class), provider, mock(ValidationLedger.class),
            mock(ToolCallJournalFactory.class), mock(RunCheckpointRepository.class), mock(WorkProductService.class),
            mock(SkillDraftService.class), mock(FileWorkbenchService.class), documents, mock(AttachmentService.class),
            dataDir, references, mock(SessionNotes.class), new StorageHealthService());
        BrowserHandoffBroker handoffs = new BrowserHandoffBroker();
        BrowserEvidenceService evidence = new BrowserEvidenceService(dataDir, new ObjectMapper());
        service.setBrowserServices(handoffs, evidence);
        var future = handoffs.request(new BrowserHandoffBroker.PendingHandoff("h1", session.id(),
            session.id() + "::browser::b1", "b1", "auth", "登录", "", Instant.now(), null), Duration.ofSeconds(10));
        evidence.record(new BrowserEvidenceRecord("ev1", session.id(), session.id(), "r1", "", Instant.now(),
            "navigate", "ok", "http://127.0.0.1:5173/", "", "ok", "", "", "", "", 0L, List.of(), 0, 0, ""));

        assertTrue(service.delete(session.id()));
        verify(backend).closeSessionTree(session.id());
        assertFalse(future.join().completed());
        assertTrue(evidence.list(session.id(), "", 10).isEmpty());
    }

    private SessionCleanupService cleanup(SessionService sessions, DocumentService documents, PilotDataDir dataDir,
        FileWorkbenchService fileWorkbench) {
        @SuppressWarnings("unchecked")
        ObjectProvider<BrowserBackend> browser = mock(ObjectProvider.class);
        when(browser.getIfAvailable()).thenReturn(null);
        RunStarter runs = mock(RunStarter.class);
        when(runs.activeRun(anyString())).thenReturn(Optional.empty());
        AttachmentReferences references = mock(AttachmentReferences.class);
        when(references.retainedOwners()).thenReturn(Set.of());
        return new SessionCleanupService(sessions, mock(ConversationRepository.class), mock(PlanRepository.class),
            mock(RunRepository.class), mock(SandboxStore.class), runs, mock(TaskControlService.class),
            mock(CommandTaskManager.class), browser, mock(ValidationLedger.class), mock(ToolCallJournalFactory.class),
            mock(RunCheckpointRepository.class), mock(WorkProductService.class), mock(SkillDraftService.class),
            fileWorkbench, documents, mock(AttachmentService.class), dataDir, references, mock(SessionNotes.class),
            new StorageHealthService());
    }
}
