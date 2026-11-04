package com.pilot.server.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.approval.PendingApproval;
import com.pilot.core.event.ApprovalRequired;
import com.pilot.core.event.EventEnvelope;
import com.pilot.core.session.SessionRepository;
import com.pilot.server.results.ResultInboxService;

class MobileWorkbenchControllerTest {
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
        var snapshot = new MobileWorkbenchController(sessions, projects, broker, repository, results).snapshot();
        @SuppressWarnings("unchecked")
        var approvals = (List<MobileWorkbenchController.MobileApproval>)snapshot.get("approvals");
        assertEquals("ses_a", approvals.get(0).sessionId());
        assertEquals("ses_b", approvals.get(1).sessionId());
        assertEquals("", approvals.get(2).sessionId());
        assertEquals(1, snapshot.get("version"));
        verify(repository).eventsOfType("approval.required", now.minusSeconds(60));
    }
}
