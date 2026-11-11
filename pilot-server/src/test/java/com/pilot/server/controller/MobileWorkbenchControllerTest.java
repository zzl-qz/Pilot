package com.pilot.server.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.approval.PendingApproval;
import com.pilot.core.event.ApprovalRequired;
import com.pilot.core.event.EventEnvelope;
import com.pilot.core.session.SessionRepository;
import com.pilot.server.config.AttachmentLimits;
import com.pilot.server.results.ResultInboxService;

import tools.jackson.databind.ObjectMapper;

class MobileWorkbenchControllerTest {
    @Test
    void unchangedSnapshotIsSmallAndReceiptChangesInvalidateIt() {
        var results = mock(ResultInboxService.class);
        var controller = controller(results);
        when(results.list()).thenReturn(new ResultInboxService.Page(List.of(item("res_a", "摘要", false)), false));
        var full = controller.snapshot(null);
        String revision = (String)full.get("revision");
        assertTrue(revision.matches("[a-f0-9]{64}"));
        assertEquals(revision, controller.snapshot(null).get("revision"));
        var unchanged = controller.snapshot(revision);
        assertEquals(Map.of("version", 1, "revision", revision, "unchanged", true), unchanged);
        assertTrue(new ObjectMapper().writeValueAsBytes(unchanged).length < 160);
        assertTrue(controller.snapshot("old").containsKey("sessions"));
        when(results.list()).thenReturn(new ResultInboxService.Page(List.of(item("res_a", "摘要", true)), false));
        assertNotEquals(revision, controller.snapshot(revision).get("revision"));
        assertFalse(controller.snapshot(revision).containsKey("unchanged"));
    }

    @Test
    void firstScreenKeepsAllResultsButLoadsHeavyDetailsOnDemand() {
        var results = mock(ResultInboxService.class);
        var controller = controller(results);
        String text = "🌏".repeat(300);
        var items = IntStream.range(0, 500).mapToObj(i -> item("res_" + i, text, false)).toList();
        when(results.list()).thenReturn(new ResultInboxService.Page(items, true));
        var snapshot = controller.snapshot(null);
        var page = (MobileWorkbenchController.MobileResultPage)snapshot.get("results");
        assertEquals(500, page.items().size());
        assertTrue(page.truncated());
        assertEquals("🌏".repeat(240) + "…", page.items().getFirst().summary());
        var mapper = new ObjectMapper();
        String encoded = mapper.writeValueAsString(page);
        assertFalse(encoded.contains("changedFiles"));
        assertFalse(encoded.contains("checks"));
        assertFalse(encoded.contains("workspace"));
        assertTrue(mapper.writeValueAsBytes(
            page).length < mapper.writeValueAsBytes(new ResultInboxService.Page(items, true)).length / 3);
        when(results.list()).thenReturn(new ResultInboxService.Page(List.of(item("res_null", null, false)), false));
        assertEquals("", ((MobileWorkbenchController.MobileResultPage)controller.snapshot(null).get("results")).items()
            .getFirst().summary());
    }

    private static MobileWorkbenchController controller(ResultInboxService results) {
        return controller(results, AttachmentLimits.defaults());
    }

    private static MobileWorkbenchController controller(ResultInboxService results, AttachmentLimits limits) {
        var sessions = mock(SessionController.class);
        var projects = mock(ProjectController.class);
        var broker = mock(ApprovalBroker.class);
        when(sessions.list(null)).thenReturn(List.of());
        when(projects.list()).thenReturn(List.of());
        when(broker.pending()).thenReturn(List.of());
        return new MobileWorkbenchController(sessions, projects, broker, mock(SessionRepository.class), results,
            new ObjectMapper(), limits);
    }

    private static ResultInboxService.Item item(String id, String summary, boolean read) {
        return new ResultInboxService.Item(id, "rev", "ses_a", "测试结果", "session", "COMPLETED", summary,
            Instant.parse("2026-11-04T00:00:00Z"), "run_a", "", read, false, List.of("src/".repeat(1500)),
            List.of("测试通过".repeat(500)), List.of(), null);
    }

    @Test
    void approvalKeepsOwningTaskAndUnknownOwnershipIsNeverGuessed() {
        SessionController sessions = mock(SessionController.class);
        ProjectController projects = mock(ProjectController.class);
        ApprovalBroker broker = mock(ApprovalBroker.class);
        SessionRepository repository = mock(SessionRepository.class);
        ResultInboxService results = mock(ResultInboxService.class);
        Instant now = Instant.now();
        var page = new ResultInboxService.Page(List.of(), false);
        when(results.list()).thenReturn(page);
        when(sessions.list(null)).thenReturn(List.of());
        when(projects.list()).thenReturn(List.of());
        when(broker.pending()).thenReturn(List.of(new PendingApproval("call_a", "run_command", "A 命令", now),
            new PendingApproval("call_b", "edit_file", "B 改动", now),
            new PendingApproval("call_pending", "run_command", "正在发布事件", now)));
        var events = List.of(
            new EventEnvelope(1, now, "approval.required", "ses_a", "run_a",
                new ApprovalRequired("call_a", "run_command", "A 命令")),
            new EventEnvelope(2, now, "approval.required", "ses_b", "run_b",
                new ApprovalRequired("call_b", "edit_file", "B 改动")));
        when(repository.eventsOfType(eq("approval.required"), any())).thenReturn(events);
        var snapshot = new MobileWorkbenchController(sessions, projects, broker, repository, results,
            new ObjectMapper(), AttachmentLimits.defaults()).snapshot(null);
        @SuppressWarnings("unchecked")
        var approvals = (List<MobileWorkbenchController.MobileApproval>)snapshot.get("approvals");
        assertEquals("ses_a", approvals.get(0).sessionId());
        assertEquals("ses_b", approvals.get(1).sessionId());
        assertEquals("", approvals.get(2).sessionId());
        assertEquals(1, snapshot.get("version"));
        verify(repository).eventsOfType("approval.required", now.minusSeconds(60));
    }

    @Test
    void advertisesAttachmentLimitsFromConfig() {
        var results = mock(ResultInboxService.class);
        when(results.list()).thenReturn(new ResultInboxService.Page(List.of(), false));
        var controller = controller(results, new AttachmentLimits(64, 2 * 1024 * 1024, 25 * 1024 * 1024, 20_000));
        @SuppressWarnings("unchecked")
        var capabilities = (Map<String, Object>)controller.snapshot(null).get("capabilities");
        assertEquals(6L * 1024 * 1024, capabilities.get("attachmentsMaxBytes"));
        assertEquals(2L * 1024 * 1024, capabilities.get("attachmentsMaxFileBytes"));
        assertEquals(16, capabilities.get("attachmentsMaxFiles"));
        assertEquals(AttachmentLimits.defaults().mobile().maxRequestBytes(),
            capabilities.get("attachmentsMaxRequestBytes"));
    }

    @Test
    void smallerConfiguredLimitsAlsoApplyToMobile() {
        var results = mock(ResultInboxService.class);
        when(results.list()).thenReturn(new ResultInboxService.Page(List.of(), false));
        var controller = controller(results, new AttachmentLimits(3, 100, 200, 100));
        var capabilities = (Map<?, ?>)controller.snapshot(null).get("capabilities");
        assertEquals(3, capabilities.get("attachmentsMaxFiles"));
        assertEquals(100L, capabilities.get("attachmentsMaxFileBytes"));
        assertEquals(200L, capabilities.get("attachmentsMaxBytes"));
    }
}
