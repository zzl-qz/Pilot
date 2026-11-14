package com.pilot.server.work;

import java.nio.file.Files;
import java.util.function.Supplier;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pilot.server.common.web.ApiErrors;

import tools.jackson.databind.JsonNode;

import lombok.RequiredArgsConstructor;

/** 产物工作台：人直接读写可编辑源并导出，不经过模型。 */
@RestController
@RequestMapping("/api/sessions/{sessionId}/studio")
@RequiredArgsConstructor
public class DocumentController {
    private final DocumentService documents;

    @GetMapping("/documents")
    public ResponseEntity<?> list(@PathVariable String sessionId) {
        return respond(() -> documents.list(sessionId));
    }

    @PostMapping("/documents")
    public ResponseEntity<?> create(@PathVariable String sessionId, @RequestBody(required = false) JsonNode body) {
        return respond(() -> documents.create(sessionId, text(body, "title"), text(body, "kind"), text(body, "spec"),
            text(body, "created_by")));
    }

    @PostMapping("/documents/import")
    public ResponseEntity<?> importProduct(@PathVariable String sessionId,
        @RequestBody(required = false) JsonNode body) {
        return respond(() -> documents.importProduct(sessionId, text(body, "product_id"), text(body, "title")));
    }

    @GetMapping("/documents/{id}")
    public ResponseEntity<?> get(@PathVariable String sessionId, @PathVariable String id) {
        return respond(() -> documents.get(sessionId, id));
    }

    @PutMapping("/documents/{id}")
    public ResponseEntity<?> update(@PathVariable String sessionId, @PathVariable String id,
        @RequestBody(required = false) JsonNode body) {
        return respond(() -> documents.update(sessionId, id, number(body, "version"), text(body, "title"),
            text(body, "spec"), text(body, "created_by")));
    }

    @GetMapping("/documents/{id}/versions")
    public ResponseEntity<?> versions(@PathVariable String sessionId, @PathVariable String id) {
        return respond(() -> documents.versions(sessionId, id));
    }

    @PostMapping("/documents/{id}/rollback")
    public ResponseEntity<?> rollback(@PathVariable String sessionId, @PathVariable String id,
        @RequestBody(required = false) JsonNode body) {
        return respond(() -> documents.rollback(sessionId, id, number(body, "version"), number(body, "target_version"),
            text(body, "created_by")));
    }

    @PostMapping("/documents/{id}/render")
    public ResponseEntity<?> render(@PathVariable String sessionId, @PathVariable String id,
        @RequestBody(required = false) JsonNode body) {
        return respond(() -> documents.render(sessionId, id, body));
    }

    @GetMapping("/documents/{id}/artifacts")
    public ResponseEntity<?> artifacts(@PathVariable String sessionId, @PathVariable String id) {
        return respond(() -> documents.artifacts(sessionId, id));
    }

    @GetMapping("/documents/{id}/artifacts/{artifactId}/download")
    public ResponseEntity<?> download(@PathVariable String sessionId, @PathVariable String id,
        @PathVariable String artifactId) {
        try {
            var download = documents.download(sessionId, id, artifactId);
            byte[] bytes = Files.readAllBytes(download.file());
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.filename() + "\"")
                .contentType(MediaType.parseMediaType(download.mediaType())).body(bytes);
        } catch (IllegalArgumentException e) {
            return ApiErrors.error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return ApiErrors.error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage() == null ? "下载失败" : e.getMessage());
        }
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.path(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static long number(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.path(field);
        return value != null && value.isNumber() ? value.asLong() : -1;
    }

    private static <T> ResponseEntity<?> respond(Supplier<T> action) {
        try {
            return ResponseEntity.ok(action.get());
        } catch (IllegalArgumentException e) {
            return ApiErrors.error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return ApiErrors.error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage() == null ? "操作失败" : e.getMessage());
        }
    }
}
