package com.pilot.server.memory;

import java.nio.file.Path;
import java.util.List;

import org.springframework.stereotype.Service;

import com.pilot.core.memory.MemoryStore;
import com.pilot.core.memory.MemoryType;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.store.PilotDataDir;
import com.pilot.server.workspace.WorkspaceService;

/** 长期记忆服务：统一把候选草稿和正式记忆保存在 Pilot 数据目录，与目标代码仓库隔离。 */
@Service
public class MemoryService {
    public record Overview(String workspace, String projectKey, String projectRoot, String storagePath,
        List<MemoryStore.Draft> drafts, List<MemoryStore.Entry> projectEntries, List<MemoryStore.Entry> userEntries,
        boolean hasLegacyDrafts) {
    }

    private final MemoryStore store;
    private final WorkspaceService workspaces;
    private final AgentProperties properties;

    public MemoryService(PilotDataDir dataDir, WorkspaceService workspaces, AgentProperties properties) {
        this.store = new MemoryStore(dataDir.file("memory"));
        this.workspaces = workspaces;
        this.properties = properties;
    }

    public Overview overview(String rawWorkspace) {
        Path workspace = resolveWorkspace(rawWorkspace);
        Path projectRoot = MemoryStore.resolveProjectRoot(workspace);
        String key = MemoryStore.projectKey(workspace);
        return new Overview(workspace.toString(), key, projectRoot.toString(), store.root().toString(),
            store.listDrafts(workspace), store.listEntries(workspace, MemoryStore.SCOPE_PROJECT),
            store.listEntries(workspace, MemoryStore.SCOPE_USER), store.hasLegacyDraftFile(workspace));
    }

    public int draftCount(String rawWorkspace) {
        return store.draftCount(resolveWorkspace(rawWorkspace));
    }

    public MemoryStore.ImportResult importLegacyDrafts(String rawWorkspace) {
        return store.importLegacyDraftFile(resolveWorkspace(rawWorkspace));
    }

    public MemoryStore.Entry approveDraft(String rawWorkspace, String draftId, String scope, String rawType,
        String title, String note, String why) {
        Path workspace = resolveWorkspace(rawWorkspace);
        MemoryType type = rawType == null || rawType.isBlank() ? null : MemoryType.parse(rawType);
        String targetScope = scope == null || scope.isBlank() ? MemoryStore.SCOPE_PROJECT : scope;
        return store.approveDraft(workspace, draftId, targetScope, type, title, note, why);
    }

    public void discardDraft(String rawWorkspace, String draftId) {
        Path workspace = resolveWorkspace(rawWorkspace);
        store.discardDraft(workspace, draftId);
    }

    public MemoryStore.Entry createEntry(String rawWorkspace, String scope, String rawType, String title, String note,
        String why) {
        Path workspace = resolveWorkspace(rawWorkspace);
        MemoryType type = MemoryType.parseOrDefault(rawType, MemoryType.FEEDBACK);
        String targetScope = scope == null || scope.isBlank() ? MemoryStore.SCOPE_PROJECT : scope;
        return store.saveEntry(workspace, targetScope, null, type, title, note, why);
    }

    public MemoryStore.Entry updateEntry(String rawWorkspace, String scope, String slug, String rawType, String title,
        String note, String why) {
        Path workspace = resolveWorkspace(rawWorkspace);
        MemoryType type = MemoryType.parseOrDefault(rawType, MemoryType.FEEDBACK);
        String targetScope = scope == null || scope.isBlank() ? MemoryStore.SCOPE_PROJECT : scope;
        return store.saveEntry(workspace, targetScope, slug, type, title, note, why);
    }

    public void deleteEntry(String rawWorkspace, String scope, String slug) {
        Path workspace = resolveWorkspace(rawWorkspace);
        String targetScope = scope == null || scope.isBlank() ? MemoryStore.SCOPE_PROJECT : scope;
        store.deleteEntry(workspace, targetScope, slug);
    }

    private Path resolveWorkspace(String rawWorkspace) {
        String candidate = rawWorkspace == null || rawWorkspace.isBlank() ? properties.workspace() : rawWorkspace;
        return workspaces.requireDirectory(candidate);
    }
}
