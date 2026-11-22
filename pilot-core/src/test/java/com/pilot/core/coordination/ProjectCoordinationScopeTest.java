package com.pilot.core.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.project.InMemoryProjectRepository;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionStatus;

class ProjectCoordinationScopeTest {

    private static Session session(String id, String workspace, String projectId) {
        return new Session(id, "会话 " + id, Instant.parse("2026-01-01T00:00:00Z"), SessionStatus.CREATED, workspace,
            projectId);
    }

    @Test
    void primaryAndAttachedFoldersBothJoinTheScope(@TempDir Path root) throws IOException {
        Path primary = Files.createDirectories(root.resolve("web"));
        Path docs = Files.createDirectories(root.resolve("docs"));

        ProjectCoordinationScope scope = ProjectCoordinationScope.of("p1", List.of(primary, docs), false);

        assertEquals("p1", scope.projectId());
        assertEquals(2, scope.directories().size());
        assertTrue(scope.includes(primary.resolve("src/App.tsx")));
        assertTrue(scope.includes(docs.resolve("guide.md")));
        assertFalse(scope.includes(root.resolve("elsewhere/notes.md")));
    }

    @Test
    void sessionsWithDifferentPrimariesShareAttachedFolder(@TempDir Path root) throws IOException {
        Path web = Files.createDirectories(root.resolve("web"));
        Path backend = Files.createDirectories(root.resolve("backend"));
        Path docs = Files.createDirectories(root.resolve("docs"));
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        projects.create("p1", "项目", List.of(web.toString(), backend.toString(), docs.toString()), Instant.now());
        CoordinationScopeResolver resolver = new CoordinationScopeResolver(projects, false);

        ProjectCoordinationScope webSession = resolver.resolve(session("s-web", web.toString(), "p1"));
        ProjectCoordinationScope backendSession = resolver.resolve(session("s-back", backend.toString(), "p1"));

        assertTrue(webSession.sharesCoordinationWith(backendSession));
        assertTrue(webSession.includes(docs.resolve("guide.md")));
    }

    @Test
    void differentProjectsStayIsolatedEvenWithSharedFolder(@TempDir Path root) throws IOException {
        Path shared = Files.createDirectories(root.resolve("shared"));
        ProjectCoordinationScope one = ProjectCoordinationScope.of("p1", List.of(shared), false);
        ProjectCoordinationScope two = ProjectCoordinationScope.of("p2", List.of(shared), false);

        assertTrue(one.intersects(two));
        assertFalse(one.sharesCoordinationWith(two));
    }

    @Test
    void sessionWithoutProjectOnlyCoordinatesItsPrimaryFolder(@TempDir Path root) throws IOException {
        Path mine = Files.createDirectories(root.resolve("mine"));
        Path other = Files.createDirectories(root.resolve("other"));
        CoordinationScopeResolver resolver = new CoordinationScopeResolver(new InMemoryProjectRepository(), false);

        ProjectCoordinationScope scope = resolver.resolve(session("s1", mine.toString(), null));

        assertNull(scope.projectId());
        assertEquals(List.of(ProjectCoordinationScope.canonical(mine)), scope.directories());
        assertFalse(scope.includes(other.resolve("file.txt")));
        assertFalse(scope.sharesCoordinationWith(ProjectCoordinationScope.ofDirectory(other, false)));
    }

    @Test
    void parentAndChildDirectoriesAreOneContiguousScope(@TempDir Path root) throws IOException {
        Path parent = Files.createDirectories(root.resolve("repo"));
        Path child = Files.createDirectories(parent.resolve("module"));
        ProjectCoordinationScope scope = ProjectCoordinationScope.of("p1", List.of(parent, child), false);

        assertEquals(List.of(ProjectCoordinationScope.canonical(parent)), scope.directories());
        assertTrue(scope.includes(child.resolve("src/Main.java")));
        assertTrue(scope.intersects(ProjectCoordinationScope.ofDirectory(child, false)));
    }

    @Test
    void siblingDirectoriesDoNotOverlap(@TempDir Path root) throws IOException {
        Path a = Files.createDirectories(root.resolve("alpha"));
        Path b = Files.createDirectories(root.resolve("beta"));
        ProjectCoordinationScope one = ProjectCoordinationScope.of("p1", List.of(a), false);
        ProjectCoordinationScope two = ProjectCoordinationScope.of("p1", List.of(b), false);

        assertFalse(one.intersects(two));
        assertFalse(one.sharesCoordinationWith(two));
    }

    @Test
    void symbolicLinksResolveToTheSameDirectory(@TempDir Path root) throws IOException {
        Path real = Files.createDirectories(root.resolve("real"));
        Path link = root.resolve("link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "当前环境不允许创建符号链接：" + e.getMessage());
        }

        ProjectCoordinationScope byLink = ProjectCoordinationScope.ofDirectory(link, false);
        ProjectCoordinationScope byReal = ProjectCoordinationScope.ofDirectory(real, false);

        assertEquals(byReal.directories(), byLink.directories());
        assertTrue(byLink.includes(real.resolve("nested/file.txt")));
    }

    @Test
    void windowsPathComparisonIgnoresCaseOnlyWhenAsked(@TempDir Path root) {
        Path mixed = root.resolve("Work");
        Path lower = root.resolve("work");

        ProjectCoordinationScope insensitive = ProjectCoordinationScope.of("p1", List.of(mixed), true);
        ProjectCoordinationScope sensitive = ProjectCoordinationScope.of("p1", List.of(mixed), false);

        assertTrue(insensitive.includes(lower.resolve("src/App.tsx")));
        assertFalse(sensitive.includes(lower.resolve("src/App.tsx")));
        assertTrue(insensitive.sharesCoordinationWith(ProjectCoordinationScope.ofDirectory(lower, true)));
        assertFalse(sensitive.sharesCoordinationWith(ProjectCoordinationScope.ofDirectory(lower, false)));
    }

    @Test
    void missingFoldersKeepTheirNormalizedPathWithoutFailing(@TempDir Path root) {
        Path missing = root.resolve("not-created-yet");

        ProjectCoordinationScope scope = ProjectCoordinationScope.of("p1", List.of(missing), false);

        assertEquals(1, scope.directories().size());
        assertTrue(scope.includes(missing.resolve("file.txt")));
    }

    @Test
    void scopeEntryPointFindsSessionsSharingAnAttachedFolder(@TempDir Path root) throws IOException {
        Path web = Files.createDirectories(root.resolve("web"));
        Path backend = Files.createDirectories(root.resolve("backend"));
        Path docs = Files.createDirectories(root.resolve("docs"));
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        projects.create("p1", "项目", List.of(web.toString(), backend.toString(), docs.toString()), Instant.now());
        CoordinationScopeResolver resolver = new CoordinationScopeResolver(projects, false);
        List<Session> sessions =
            List.of(session("s-web", web.toString(), "p1"), session("s-back", backend.toString(), "p1"));

        ProjectCoordinationScope fromBackend = resolver.scopeForWorkspace(backend.toString(), sessions);
        ProjectCoordinationScope fromWeb = resolver.scopeForWorkspace(web.toString(), sessions);

        assertTrue(fromBackend.sharesCoordinationWith(fromWeb));
        assertEquals("p1", fromWeb.projectId());
    }
}
