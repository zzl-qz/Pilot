package com.pilot.server.memory;

import java.io.UncheckedIOException;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pilot.core.memory.MemoryStore;
import com.pilot.server.api.ApiErrors;

import lombok.RequiredArgsConstructor;

/** 外置记忆候选区与项目/个人记忆管理接口。 */
@RestController
@RequestMapping("/api/memory")
@RequiredArgsConstructor
public class MemoryController {
    private final MemoryService memories;

    public record ApproveRequest(String workspace, String scope, String type, String title, String note, String why) {
    }

    public record EntryRequest(String workspace, String scope, String type, String title, String note, String why) {
    }

    public record ImportRequest(String workspace) {
    }

    @GetMapping
    public MemoryService.Overview overview(@RequestParam(name = "workspace", required = false) String workspace) {
        return memories.overview(workspace);
    }

    @GetMapping("/drafts/count")
    public Map<String, Integer> draftCount(@RequestParam(name = "workspace", required = false) String workspace) {
        return Map.of("count", memories.draftCount(workspace));
    }

    @PostMapping("/import-legacy")
    public MemoryStore.ImportResult importLegacy(@RequestBody(required = false) ImportRequest request) {
        return memories.importLegacyDrafts(request == null ? null : request.workspace());
    }

    @PostMapping("/drafts/{id}/approve")
    public MemoryStore.Entry approve(@PathVariable String id, @RequestBody(required = false) ApproveRequest request) {
        ApproveRequest body = request == null ? new ApproveRequest(null, null, null, null, null, null) : request;
        return memories.approveDraft(body.workspace(), id, body.scope(), body.type(), body.title(), body.note(),
            body.why());
    }

    @DeleteMapping("/drafts/{id}")
    public ResponseEntity<Void> discard(@PathVariable String id,
        @RequestParam(name = "workspace", required = false) String workspace) {
        memories.discardDraft(workspace, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/entries")
    public MemoryStore.Entry create(@RequestBody(required = false) EntryRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        return memories.createEntry(request.workspace(), request.scope(), request.type(), request.title(),
            request.note(), request.why());
    }

    @PutMapping("/entries/{slug}")
    public MemoryStore.Entry update(@PathVariable String slug, @RequestBody(required = false) EntryRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        return memories.updateEntry(request.workspace(), request.scope(), slug, request.type(), request.title(),
            request.note(), request.why());
    }

    @DeleteMapping("/entries/{slug}")
    public ResponseEntity<Void> delete(@PathVariable String slug,
        @RequestParam(name = "workspace", required = false) String workspace,
        @RequestParam(name = "scope", defaultValue = "project") String scope) {
        memories.deleteEntry(workspace, scope, slug);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> missing(NoSuchElementException e) {
        return ApiErrors.message(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ApiErrors.badRequest(e);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ApiErrors.conflict(e);
    }

    @ExceptionHandler(UncheckedIOException.class)
    public ResponseEntity<Map<String, String>> ioFailure(UncheckedIOException e) {
        return ApiErrors.message(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }
}
