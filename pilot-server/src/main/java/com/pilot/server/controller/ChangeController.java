package com.pilot.server.controller;

import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.workspace.ChangeService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;

/** 会话改动审阅：读取该会话工作区的 git 状态与 diff。 */
@RestController
@RequestMapping("/api/sessions/{id}")
public class ChangeController {
    private final SessionService sessions;
    private final ChangeService changes;
    private final AgentProperties properties;

    public ChangeController(SessionService sessions, ChangeService changes, AgentProperties properties) {
        this.sessions = sessions;
        this.changes = changes;
        this.properties = properties;
    }

    @GetMapping("/diff")
    public ChangeService.ChangeReport diff(@PathVariable String id) {
        Session session = sessions.find(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found: " + id));
        String workspace = session.workspace() == null || session.workspace().isBlank()
                ? properties.workspace()
                : session.workspace();
        return changes.inspect(Path.of(workspace));
    }
}
