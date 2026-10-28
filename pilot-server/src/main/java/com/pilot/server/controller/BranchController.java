package com.pilot.server.controller;

import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.session.RunStarter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/** 在当前工作区创建并切换 Git 分支；未提交改动仍在当前工作区。 */
@RestController
@RequestMapping("/api/sessions/{id}/branch")
public class BranchController {
    private final SessionService sessions;
    private final AgentProperties properties;
    private final RunStarter runs;

    public BranchController(SessionService sessions, AgentProperties properties, RunStarter runs) {
        this.sessions = sessions;
        this.properties = properties;
        this.runs = runs;
    }

    @PostMapping
    public BranchResponse create(@PathVariable String id, @RequestBody BranchRequest request) {
        Session session = sessions.find(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found: " + id));
        String name = request == null ? null : request.name();
        if (name == null || name.isBlank() || name.length() > 200 || name.startsWith("-")
                || name.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid branch name");
        }
        String workspace = session.workspace() == null || session.workspace().isBlank()
                ? properties.workspace() : session.workspace();
        Path root = Path.of(workspace).toAbsolutePath().normalize();
        List<String> command = List.of("git", "-C", root.toString(), "switch", "-c", name);
        String slot = runs.claimRun(id).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT, "请先等待当前运行结束再创建分支"));
        try {
            for (Session other : sessions.list()) {
                if (!other.id().equals(id) && root.toString().equals(other.workspace())
                        && runs.activeRun(other.id()).isPresent()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "同一工作区有其他对话正在运行");
                }
            }
            if (!runs.lockWorkspace(slot, root)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "同一工作区有任务正在运行或回退，请稍后重试");
            }
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "git branch creation timed out");
            }
            byte[] output = process.getInputStream().readNBytes(16_384);
            if (process.exitValue() != 0) {
                String message = new String(output, StandardCharsets.UTF_8).strip();
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        message.isBlank() ? "unable to create branch" : message);
            }
            return new BranchResponse(name, root.toString());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workspace is not a usable git repository", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "git branch creation interrupted", e);
        } finally {
            runs.abandon(slot);
        }
    }

    public record BranchRequest(String name) { }
    public record BranchResponse(String name, String workspace) { }
}
