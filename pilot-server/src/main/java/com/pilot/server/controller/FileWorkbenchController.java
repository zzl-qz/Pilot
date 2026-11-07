package com.pilot.server.controller;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pilot.server.work.FileWorkbenchService;

import tools.jackson.databind.JsonNode;

/** 文件工作台：所有操作都指向会话工作区里的真实文件，不维护第二套可编辑源。 */
@RestController
/** 单独命名空间，避免与 {@code SessionFilesController} 的工作区文件树 /files 撞路由。 */
@RequestMapping("/api/sessions/{sessionId}/workbench/files")
public class FileWorkbenchController {
    private final FileWorkbenchService files;

    public FileWorkbenchController(FileWorkbenchService files) {
        this.files = files;
    }

    @GetMapping
    public ResponseEntity<?> list(@PathVariable String sessionId) throws IOException {
        return ResponseEntity.ok(files.list(sessionId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> asset(@PathVariable String sessionId, @PathVariable String id) throws IOException {
        return ResponseEntity.ok(files.asset(sessionId, id));
    }

    @GetMapping("/{id}/text")
    public ResponseEntity<?> text(@PathVariable String sessionId, @PathVariable String id) throws IOException {
        return ResponseEntity.ok(Map.of("text", files.inspect(sessionId, id)));
    }

    @GetMapping("/{id}/preview")
    public ResponseEntity<?> preview(@PathVariable String sessionId, @PathVariable String id) throws IOException {
        return ResponseEntity.ok(files.preview(sessionId, id));
    }

    @GetMapping("/{id}/preview/{page}")
    public ResponseEntity<byte[]> previewPage(@PathVariable String sessionId, @PathVariable String id,
        @PathVariable int page) throws IOException {
        FileWorkbenchService.Download image = files.previewPage(sessionId, id, page);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(image.content());
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable String sessionId, @PathVariable String id) throws IOException {
        FileWorkbenchService.Download file = files.download(sessionId, id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + URLEncoder.encode(file.filename(), StandardCharsets.UTF_8))
            .body(file.content());
    }

    /** 由后端所在机器调用系统默认应用；能力不足时返回明确的替代操作。 */
    @PostMapping("/{id}/open")
    public ResponseEntity<?> open(@PathVariable String sessionId, @PathVariable String id) throws IOException {
        return ResponseEntity.ok(files.open(sessionId, id));
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<?> publish(@PathVariable String sessionId, @PathVariable String id,
        @RequestBody(required = false) JsonNode body) throws IOException {
        String title = body == null || body.path("title").isMissingNode() ? "" : body.path("title").asText("");
        String summary = body == null || body.path("summary").isMissingNode() ? "" : body.path("summary").asText("");
        return ResponseEntity.ok(files.publish(sessionId, id, title, summary));
    }

    @GetMapping("/{id}/versions")
    public ResponseEntity<?> versions(@PathVariable String sessionId, @PathVariable String id) {
        return ResponseEntity.ok(files.versions(sessionId, id));
    }

    @PostMapping("/{id}/versions")
    public ResponseEntity<?> snapshot(@PathVariable String sessionId, @PathVariable String id,
        @RequestBody(required = false) JsonNode body) throws IOException {
        String label = body == null ? "" : body.path("label").asText("");
        return ResponseEntity.ok(files.snapshot(sessionId, id, label));
    }

    @PostMapping("/{id}/revert")
    public ResponseEntity<?> revert(@PathVariable String sessionId, @PathVariable String id, @RequestBody JsonNode body)
        throws IOException {
        return ResponseEntity.ok(files.revert(sessionId, id, body.path("version").asLong()));
    }

    @GetMapping("/{id}/annotations")
    public ResponseEntity<?> annotations(@PathVariable String sessionId, @PathVariable String id) {
        return ResponseEntity.ok(files.annotations(sessionId, id));
    }

    @PostMapping("/{id}/annotations")
    public ResponseEntity<?> annotate(@PathVariable String sessionId, @PathVariable String id,
        @RequestBody JsonNode body) throws IOException {
        return ResponseEntity.ok(files.annotate(sessionId, id, body.path("page").asInt(1),
            body.path("anchor").asText(""), body.path("text").asText("")));
    }

    /** 把批注翻译成可直接发给模型的审阅指导，前端只需把它作为一条消息发送。 */
    @PostMapping("/{id}/annotations/{annotationId}/request")
    public ResponseEntity<?> changeRequest(@PathVariable String sessionId, @PathVariable String id,
        @PathVariable String annotationId) throws IOException {
        return ResponseEntity.ok(Map.of("prompt", files.changeRequest(sessionId, id, annotationId)));
    }

    /** 表单化的 PDF 页面操作，结果另存为新文件，不覆盖源。 */
    @PostMapping("/{id}/pdf-operations")
    public ResponseEntity<?> pdfOperations(@PathVariable String sessionId, @PathVariable String id,
        @RequestBody JsonNode operations) throws IOException {
        return ResponseEntity.ok(files.pdfOperations(sessionId, id, operations));
    }

    @ExceptionHandler({IllegalArgumentException.class, IOException.class, SecurityException.class})
    public ResponseEntity<Map<String, Object>> failure(Exception error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        return ResponseEntity.badRequest().body(body);
    }
}
