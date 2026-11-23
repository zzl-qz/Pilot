package com.pilot.server.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.project.InMemoryProjectRepository;
import com.pilot.core.project.ProjectRepository;
import com.pilot.core.session.ConversationRepository;
import com.pilot.core.session.InMemoryConversationRepository;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.server.run.TaskControlService;
import com.pilot.server.run.TaskControlStatus;
import com.pilot.server.store.PilotDataDir;
import com.pilot.server.validation.ValidationEvidence;
import com.pilot.server.validation.ValidationLedger;
import com.pilot.server.validation.ValidationStatusService;
import com.pilot.server.validation.VerificationPolicyService;
import com.pilot.server.validation.WorkspaceFingerprint;
import com.pilot.server.workspace.RunSnapshotService;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class CompletionBundleAndCapsuleTest {
    @TempDir
    Path temp;

    private Path workspace;
    private SessionService sessions;
    private TaskControlService tasks;
    private ValidationLedger ledger;
    private CompletionBundleService completion;
    private TaskCapsuleService capsules;
    private ProjectRepository projects;
    private ConversationRepository conversations;
    private Session session;

    @BeforeEach
    void setUp() throws Exception {
        workspace = Files.createDirectories(temp.resolve("workspace"));
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/App.java"), "class App {}\n");
        Files.writeString(workspace.resolve("pilot.policy.yaml"), """
            version: 1
            checks:
              "src/**": "mvn -q test"
            completion:
              require: all
            """);
        ObjectMapper mapper = new ObjectMapper();
        PilotDataDir data = new PilotDataDir(temp.resolve(".pilot").toString());
        sessions = new SessionService();
        tasks = new TaskControlService(mapper, data);
        ledger = new ValidationLedger(data, mapper);
        completion = new CompletionBundleService(sessions, tasks, ledger, new ValidationStatusService(ledger),
            new VerificationPolicyService(), new RunSnapshotService(mapper, data));
        projects = new InMemoryProjectRepository();
        projects.create("prj_test", "test", List.of(workspace.toString()), null, Instant.now());
        conversations = new InMemoryConversationRepository();
        capsules = new TaskCapsuleService(completion, sessions, tasks, projects, conversations);
        session = sessions.createSession("测试任务", workspace.toString(), "prj_test", false);
        tasks.start(session.id(), "实现订单接口", List.of("不引入新依赖"),
            List.of(new TaskControlService.Acceptance("接口可用", false, "")));
    }

    @Test
    void bundleShowsEvidencePolicyAndChanges() {
        recordEvidence("mvn -q test → exit 0");

        var bundle = completion.build(session.id());

        assertEquals(1, bundle.version());
        assertEquals("实现订单接口", bundle.goal());
        assertEquals(List.of("不引入新依赖"), bundle.constraints());
        assertTrue(bundle.policy().configured());
        assertTrue(bundle.policy().passed());
        assertTrue(bundle.verification().verified());
        assertTrue(completion.markdown(bundle).contains("mvn -q test"));
        assertTrue(completion.markdown(bundle).contains("完成凭证"));
    }

    @Test
    void verifyTurnsStaleAfterArtifactChanges() throws Exception {
        recordEvidence("mvn -q test → exit 0");
        assertTrue(completion.verify(session.id()).status().equals("verified"));

        Files.writeString(workspace.resolve("src/App.java"), "class App { int n; }\n");

        var verify = completion.verify(session.id());
        assertEquals("stale", verify.status());
        assertEquals("stale_candidate", verify.code());
    }

    @Test
    void missingPolicyCheckAddsNextStep() {
        recordEvidence("mvn -q package → exit 0");

        var bundle = completion.build(session.id());

        assertFalse(bundle.policy().passed());
        assertTrue(bundle.policy().missing().contains("mvn -q test"));
        assertTrue(bundle.nextSteps().stream().anyMatch(step -> step.contains("mvn -q test")));
    }

    @Test
    void capsuleExportAndImportRestoreContractWithoutApplyingDiff() {
        recordEvidence("mvn -q test → exit 0");

        var capsule = capsules.export(session.id());
        assertNotNull(capsule.completion());
        assertTrue(capsules.handoffText(capsule).contains("任务交接胶囊"));

        var imported = capsules.importCapsule(capsule, "prj_test", null);

        assertTrue(imported.sessionId().startsWith("ses_"));
        assertTrue(imported.note().contains("差异未自动应用"));
        assertTrue(imported.warnings().stream().anyMatch(warning -> warning.contains("来源任务状态")));
        var restored = tasks.load(imported.sessionId());
        assertNotNull(restored);
        assertEquals("实现订单接口", restored.originalGoal());
        assertEquals(TaskControlStatus.WAITING.name(), restored.status());
        var messages = conversations.load(imported.sessionId());
        assertEquals(1, messages.size());
        assertTrue(messages.getFirst().content().contains("任务交接胶囊"));
        // 导入只恢复契约与说明，不自动创建/修改工作区文件。
        assertFalse(Files.exists(Path.of(imported.workspace()).resolve("imported.txt")));
    }

    @Test
    void capsuleRejectsUnknownProjectAndVersion() {
        var capsule = capsules.export(session.id());

        assertThrows(IllegalArgumentException.class, () -> capsules.importCapsule(capsule, "prj_missing", null));
        var wrong =
            new TaskCapsuleService.Capsule(99, capsule.exportedAt(), capsule.sourceSessionId(), capsule.completion());
        assertThrows(IllegalArgumentException.class, () -> capsules.importCapsule(wrong, "prj_test", null));
    }

    @Test
    void capsuleJsonCanRoundTripThroughApiShape() throws Exception {
        recordEvidence("mvn -q test → exit 0");
        var capsule = capsules.export(session.id());
        ObjectMapper mapper = JsonMapper.builder().build();

        String json = mapper.writeValueAsString(capsule);
        var parsed = mapper.readValue(json, TaskCapsuleService.Capsule.class);

        assertEquals(capsule.sourceSessionId(), parsed.sourceSessionId());
        assertEquals(capsule.completion().goal(), parsed.completion().goal());
        assertEquals(capsule.completion().evidence().getFirst().checks(),
            parsed.completion().evidence().getFirst().checks());
    }

    private void recordEvidence(String check) {
        List<String> files = List.of("src/App.java");
        String candidate = WorkspaceFingerprint.of(workspace, files);
        ledger.record(new ValidationEvidence("val_test", session.id(), "run_test", candidate, files, List.of(check),
            ValidationEvidence.PASSED, "test-env", "test", Instant.now()));
    }
}
