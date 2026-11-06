package com.pilot.server.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.pilot.core.session.SessionService;

import tools.jackson.databind.ObjectMapper;

/** 审阅面板的两个新接口：撤销改动、能力清单。 */
@SpringBootTest(properties = "pilot.store=memory")
@AutoConfigureMockMvc
class ReviewApiTest {
    private static final Path DATA = Path.of(System.getProperty("java.io.tmpdir"), "pilot-review-" + UUID.randomUUID());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("pilot.data.dir", DATA::toString);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    SessionService sessions;
    @Autowired
    ObjectMapper mapper;
    @TempDir
    Path workspace;

    @Test
    void restoreEndpointRevertsTrackedAndDeletesUntracked() throws Exception {
        assumeTrue(git("--version"), "需要本机可用的 git");
        git("init", "-q");
        git("config", "core.autocrlf", "false");
        Path tracked = workspace.resolve("a.txt");
        Files.writeString(tracked, "old\n");
        git("add", ".");
        commit();

        Files.writeString(tracked, "changed\n");
        Files.writeString(workspace.resolve("new.txt"), "hello\n");
        String id = sessions.createSession("审阅撤销", workspace.toString()).id();

        mvc.perform(post("/api/sessions/{id}/diff/restore", id).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("paths", List.of("a.txt", "new.txt")))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.restored[0]").value("a.txt"))
            .andExpect(jsonPath("$.deleted[0]").value("new.txt")).andExpect(jsonPath("$.failed").isEmpty());

        assertEquals("old\n", Files.readString(tracked));
        assertFalse(Files.exists(workspace.resolve("new.txt")));
    }

    /** 越界路径必须被拒绝并逐条回报，不能悄悄成功。 */
    @Test
    void restoreEndpointRejectsPathsOutsideWorkspace() throws Exception {
        assumeTrue(git("--version"), "需要本机可用的 git");
        git("init", "-q");
        String id = sessions.createSession("越界撤销", workspace.toString()).id();

        mvc.perform(post("/api/sessions/{id}/diff/restore", id).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("paths", List.of("../escape.txt"))))).andExpect(status().isOk())
            .andExpect(jsonPath("$.restored").isEmpty()).andExpect(jsonPath("$.failed[0].path").value("../escape.txt"))
            .andExpect(jsonPath("$.failed[0].reason").isNotEmpty());
    }

    /**
     * 整份补丁被截断后，界面点开某个文件要能单独拿到它的补丁，
     * 而不是拿文件正文冒充 diff。
     */
    @Test
    void fileDiffReturnsPatchForOneFile() throws Exception {
        assumeTrue(git("--version"), "需要本机可用的 git");
        git("init", "-q");
        git("config", "core.autocrlf", "false");
        Path tracked = workspace.resolve("a.txt");
        Files.writeString(tracked, "one\ntwo\n");
        git("add", ".");
        commit();
        Files.writeString(tracked, "one\nTWO\n");
        Files.writeString(workspace.resolve("new.txt"), "hello\n");
        String id = sessions.createSession("单文件补丁", workspace.toString()).id();

        mvc.perform(get("/api/sessions/{id}/diff/file", id).param("path", "a.txt")).andExpect(status().isOk())
            .andExpect(jsonPath("$.path").value("a.txt")).andExpect(jsonPath("$.added").value(1))
            .andExpect(jsonPath("$.removed").value(1)).andExpect(jsonPath("$.truncated").value(false))
            .andExpect(jsonPath("$.diff").value(Matchers.containsString("diff --git a/a.txt b/a.txt")))
            .andExpect(jsonPath("$.diff").value(Matchers.containsString("+TWO")));

        // 未跟踪文件按当前内容合成全新增补丁，不能返回空
        mvc.perform(get("/api/sessions/{id}/diff/file", id).param("path", "new.txt")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("??")).andExpect(jsonPath("$.added").value(1))
            .andExpect(jsonPath("$.diff").value(Matchers.containsString("+hello")));
    }

    @Test
    void fileDiffRejectsPathOutsideWorkspace() throws Exception {
        assumeTrue(git("--version"), "需要本机可用的 git");
        git("init", "-q");
        String id = sessions.createSession("越界补丁", workspace.toString()).id();

        mvc.perform(get("/api/sessions/{id}/diff/file", id).param("path", "../escape.txt"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/sessions/{id}/diff/file", id).param("path", " ")).andExpect(status().isBadRequest());
    }

    /** 能力页签读的就是注册表：工具必须有名字和可用性，不再是写死的说明。 */
    @Test
    void capabilitiesEndpointListsToolsAndEnvironment() throws Exception {
        String id = sessions.createSession("能力清单", workspace.toString()).id();

        mvc.perform(get("/api/sessions/{id}/capabilities", id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.workspace").value(workspace.toAbsolutePath().normalize().toString()))
            .andExpect(jsonPath("$.permissionMode").isNotEmpty()).andExpect(jsonPath("$.tools").isArray())
            .andExpect(jsonPath("$.tools[0].name").isNotEmpty()).andExpect(jsonPath("$.mcpServers").isArray())
            .andExpect(jsonPath("$.localCapabilities").isArray())
            .andExpect(jsonPath("$.localCapabilities[?(@.id=='python')]").isNotEmpty())
            .andExpect(jsonPath("$.localCapabilities[0].preparationAdvice").isNotEmpty());
    }

    /**
     * 浏览器是按会话装配的工具，不在默认注册表里；
     * 能力清单漏掉它，页签上就看不到「浏览器能不能用」。
     */
    @Test
    void capabilitiesIncludeSessionScopedBrowserTool() throws Exception {
        String id = sessions.createSession("浏览器能力", workspace.toString()).id();

        mvc.perform(get("/api/sessions/{id}/capabilities", id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.tools[?(@.name=='browser')]").isNotEmpty());
    }

    private void commit() {
        git("-c", "user.name=pilot", "-c", "user.email=pilot@example.com", "commit", "-q", "-m", "init");
    }

    private boolean git(String... args) {
        ArrayList<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workspace.toFile());
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            process.getOutputStream().close();
            process.getInputStream().readAllBytes();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            assertTrue(finished, "git 命令超时：" + String.join(" ", command));
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
