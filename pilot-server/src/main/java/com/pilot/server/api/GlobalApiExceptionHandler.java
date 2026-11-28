package com.pilot.server.api;

import java.io.UncheckedIOException;
import java.nio.file.InvalidPathException;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.util.Map;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.pilot.core.session.EventTooLargeException;
import com.pilot.core.session.SessionService;

/** Convert request and validation failures to stable JSON without exception details. */
@RestControllerAdvice
public class GlobalApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalApiExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> responseStatus(ResponseStatusException exception) {
        HttpStatusCode code = exception.getStatusCode();
        HttpStatus resolved = HttpStatus.resolve(code.value());
        String error = resolved == null ? String.valueOf(code.value()) : resolved.getReasonPhrase();
        String message =
            exception.getReason() == null || exception.getReason().isBlank() ? error : exception.getReason();
        return ResponseEntity.status(code).body(Map.of("status", code.value(), "error", error, "message", message));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "请求的资源不存在");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> payloadTooLarge(MaxUploadSizeExceededException exception) {
        return response(HttpStatus.PAYLOAD_TOO_LARGE, "上传内容超过大小限制");
    }

    @ExceptionHandler(EventTooLargeException.class)
    public ResponseEntity<Map<String, Object>> eventTooLarge(EventTooLargeException exception) {
        return response(HttpStatus.PAYLOAD_TOO_LARGE, fallbackMessage(exception.getMessage(), "事件内容超过大小限制"));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> missingElement(NoSuchElementException exception) {
        if ("No value present".equals(exception.getMessage())) {
            log.error("未处理的空值访问异常", exception);
            return response(HttpStatus.INTERNAL_SERVER_ERROR, "服务器处理请求失败");
        }
        return response(HttpStatus.NOT_FOUND, fallbackMessage(exception.getMessage(), "请求的资源不存在"));
    }

    @ExceptionHandler({SessionService.HistoryChangedException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> conflict(RuntimeException exception) {
        if (hasCause(exception, SQLException.class)) {
            log.error("数据库操作异常", exception);
            return response(HttpStatus.INTERNAL_SERVER_ERROR, "服务器处理请求失败");
        }
        log.debug("接口状态冲突: {}", exception.getMessage());
        return response(HttpStatus.CONFLICT, fallbackMessage(exception.getMessage(), "数据已变化或当前状态不允许"));
    }

    @ExceptionHandler(UncheckedIOException.class)
    public ResponseEntity<Map<String, Object>> ioUnavailable(UncheckedIOException exception) {
        log.warn("接口 IO 操作失败: {}", exception.getMessage(), exception);
        String message = exception.getMessage();
        Throwable cause = exception.getCause();
        // 避免单参 new UncheckedIOException(ioe) 直接把底层异常类名与本机路径暴露给客户端
        if (cause != null && cause.toString().equals(message)) {
            message = null;
        }
        return response(HttpStatus.SERVICE_UNAVAILABLE, fallbackMessage(message, "存储或文件操作失败，请稍后重试"));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
        HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<Map<String, Object>> malformedRequest(Exception exception) {
        for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ResponseStatusException statusException) {
                return responseStatus(statusException);
            }
        }
        return response(HttpStatus.BAD_REQUEST, "请求参数或请求体格式不正确");
    }

    @ExceptionHandler({IllegalArgumentException.class, InvalidPathException.class, DateTimeException.class})
    public ResponseEntity<Map<String, Object>> invalidArgument(RuntimeException exception) {
        String message = exception instanceof InvalidPathException pathException ? pathException.getReason()
            : exception.getMessage();
        return response(HttpStatus.BAD_REQUEST, fallbackMessage(message, "请求参数不合法"));
    }

    @ExceptionHandler({AsyncRequestNotUsableException.class, AsyncRequestTimeoutException.class})
    public void disconnected(Exception exception) {
        // SSE/长连接对端主动断开或异步超时，不向 text/event-stream 写 JSON 响应体也不刷异常堆栈。
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> fallback(Exception exception) {
        if (exception instanceof ErrorResponse errorResponse) {
            HttpStatusCode code = errorResponse.getStatusCode();
            HttpStatus resolved = HttpStatus.resolve(code.value());
            String error = resolved == null ? String.valueOf(code.value()) : resolved.getReasonPhrase();
            String detail = errorResponse.getBody().getDetail();
            String message = detail == null || detail.isBlank() ? error : detail;
            return ResponseEntity.status(code).body(Map.of("status", code.value(), "error", error, "message", message));
        }
        log.error("未处理的接口异常", exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "服务器处理请求失败");
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    private static String fallbackMessage(String message, String fallback) {
        return message == null || message.isBlank() ? fallback : message;
    }

    private static ResponseEntity<Map<String, Object>> response(HttpStatus status, String message) {
        return ResponseEntity.status(status)
            .body(Map.of("status", status.value(), "error", status.getReasonPhrase(), "message", message));
    }
}
