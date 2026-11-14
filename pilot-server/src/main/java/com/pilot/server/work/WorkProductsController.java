package com.pilot.server.work;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pilot.server.common.web.ApiErrors;

import lombok.RequiredArgsConstructor;

/** 成果读取与人工技能审核；沿用服务统一的远程访问认证。 */
@RestController
@RequestMapping("/api/sessions/{sessionId}")
@RequiredArgsConstructor
public class WorkProductsController {
    private final WorkProductService products;
    private final SkillDraftService drafts;

    @GetMapping("/products")
    public List<WorkProductService.Product> products(@PathVariable String sessionId) {
        return products.list(sessionId);
    }

    @GetMapping("/products/{id}/preview")
    public WorkProductService.Preview preview(@PathVariable String sessionId, @PathVariable String id) {
        return products.preview(products.get(sessionId, id));
    }

    @GetMapping("/products/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable String sessionId, @PathVariable String id) {
        var product = products.get(sessionId, id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(product.mediaType()))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(product.filename(), StandardCharsets.UTF_8).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Content-Type-Options", "nosniff")
            .body(products.content(product));
    }

    @GetMapping("/skill-drafts")
    public List<SkillDraftService.Draft> drafts(@PathVariable String sessionId) {
        return drafts.list(sessionId);
    }

    public record Decision(Long version) {
    }

    @PostMapping("/skill-drafts/{id}/{action}")
    public SkillDraftService.Draft decide(@PathVariable String sessionId, @PathVariable String id,
        @PathVariable String action, @RequestBody Decision request) {
        if (request == null || request.version() == null || request.version() < 1) {
            throw new IllegalArgumentException("请提供草稿当前版本");
        }
        return drafts.decide(sessionId, id, request.version(), action);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> missing(NoSuchElementException e) {
        return failure(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return failure(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return failure(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(UncheckedIOException.class)
    public ResponseEntity<Map<String, String>> ioFailure(UncheckedIOException e) {
        return failure(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    private ResponseEntity<Map<String, String>> failure(HttpStatus status, String message) {
        return ApiErrors.message(status, message == null ? "操作失败，请刷新后重试" : message);
    }
}
