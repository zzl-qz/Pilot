package com.pilot.server.session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.core.session.SessionService;
import com.pilot.core.tool.office.DocumentRenderer;
import com.pilot.server.attachment.AttachmentReferences;
import com.pilot.server.session.lookup.SessionLookup;

import lombok.RequiredArgsConstructor;

/** 会话上传附件的读取与纯文本预览接口。 */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionAttachmentController {
    private final SessionService sessions;
    private final AttachmentReferences attachmentReferences;

    /** 取回当前会话上传的附件；二进制文件用 attachment disposition 下载。 */
    @GetMapping("/{id}/attachments/{file}")
    public ResponseEntity<Resource> attachment(@PathVariable String id, @PathVariable String file,
        @RequestParam(name = "name", required = false) String originalName) {
        SessionLookup.require(sessions, id);
        Path path;
        try {
            path = attachmentReferences.resolve(id, file);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "附件不存在");
        }
        String lower = file.toLowerCase(Locale.ROOT);
        MediaType type = lower.endsWith(".png") ? MediaType.IMAGE_PNG
            : lower.endsWith(".jpg") || lower.endsWith(".jpeg") ? MediaType.IMAGE_JPEG : lower.endsWith(".gif")
                ? MediaType.IMAGE_GIF : lower.endsWith(".webp") ? MediaType.parseMediaType("image/webp") : null;
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().header("X-Content-Type-Options", "nosniff")
            .header("Cache-Control", "private, max-age=86400");
        if (type != null) {
            return response.contentType(type).body(new FileSystemResource(path));
        }
        String downloadName = safeDownloadName(originalName, path.getFileName().toString());
        return response.contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(downloadName, StandardCharsets.UTF_8).build().toString())
            .body(new FileSystemResource(path));
    }

    /** Office 附件的纯文本读回；不还原版式，也不执行附件中的 HTML/脚本。 */
    @GetMapping("/{id}/attachments/{file}/preview-text")
    public ResponseEntity<?> attachmentPreviewText(@PathVariable String id, @PathVariable String file) {
        SessionLookup.require(sessions, id);
        Path path;
        try {
            path = attachmentReferences.resolve(id, file);
            if (!DocumentRenderer.isRenderable(path.getFileName().toString())) {
                throw new IllegalArgumentException("不支持此附件的内容预览");
            }
            String text = DocumentRenderer.inspect(path);
            if (text.length() > 250_000) {
                text = text.substring(0, 250_000) + "\n…内容过长，已截断";
            }
            return ResponseEntity.ok(Map.of("text", text));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "附件内容无法读取", e);
        }
    }

    private static String safeDownloadName(String originalName, String storedName) {
        String name = originalName == null || originalName.isBlank() ? storedName.replaceFirst("^[a-fA-F0-9-]{36}-", "")
            : originalName;
        String safe = name.replaceAll("[\\\\/\\r\\n\\p{Cntrl}]", "_").strip();
        return safe.isBlank() ? "attachment" : safe;
    }
}
