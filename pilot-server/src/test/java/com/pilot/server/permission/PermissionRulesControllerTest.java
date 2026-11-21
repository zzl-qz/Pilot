package com.pilot.server.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.server.workspace.WorkspaceService;

class PermissionRulesControllerTest {
    @TempDir
    Path workspace;

    private PermissionRulesController controller() {
        return new PermissionRulesController(new WorkspaceService());
    }

    @Test
    void updateWritesRulesAndReturnsThemOnGet() throws IOException {
        PermissionRulesController controller = controller();

        PermissionRulesController.RulesResponse saved = controller.update(new PermissionRulesController.RulesRequest(
            workspace.toString(), List.of("run_command(mvn test:*)"), List.of("run_command(rm -rf:*)"),
            List.of("edit_file(docs/*.md)")));

        assertEquals(List.of("run_command(mvn test:*)"), saved.allow());
        assertEquals(List.of("run_command(rm -rf:*)"), saved.deny());
        assertEquals(List.of("edit_file(docs/*.md)"), saved.ask());
        assertTrue(saved.loadIssues().isEmpty());
        assertTrue(Files.isRegularFile(Path.of(saved.file())), "应真的写到 .pilot/settings.json");

        // 每次请求都重新读盘，改完立刻对下一次读取生效
        PermissionRulesController.RulesResponse reread = controller.get(workspace.toString());
        assertEquals(saved.deny(), reread.deny());
    }

    @Test
    void getWithoutRulesReturnsEmptyListsNotErrors() {
        PermissionRulesController.RulesResponse response = controller().get(workspace.toString());

        assertTrue(response.allow().isEmpty());
        assertTrue(response.deny().isEmpty());
        assertTrue(response.ask().isEmpty());
        assertTrue(response.loadIssues().isEmpty(), "文件还没建时不算读取问题");
        assertNotNull(response.inherited(), "用户级规则始终有结构，界面不必判空");
    }

    @Test
    void emptyUpdateRemovesRulesFromFile() throws IOException {
        PermissionRulesController controller = controller();
        controller.update(new PermissionRulesController.RulesRequest(workspace.toString(), List.of(),
            List.of("read_file(.env)"), List.of()));

        PermissionRulesController.RulesResponse cleared = controller.update(new PermissionRulesController.RulesRequest(
            workspace.toString(), List.of(), List.of(), List.of()));

        assertTrue(cleared.deny().isEmpty());
        assertFalse(Files.readString(Path.of(cleared.file())).contains("\"permissions\""),
            "清空后不应留下空 permissions 段");
    }

    @Test
    void unknownWorkspaceIsBadRequest() {
        ResponseStatusException getError = assertThrows(ResponseStatusException.class,
            () -> controller().get(workspace.resolve("missing").toString()));
        assertEquals(400, getError.getStatusCode().value());

        ResponseStatusException putError = assertThrows(ResponseStatusException.class,
            () -> controller().update(new PermissionRulesController.RulesRequest(
                workspace.resolve("missing").toString(), List.of(), List.of(), List.of())));
        assertEquals(400, putError.getStatusCode().value());
    }
}