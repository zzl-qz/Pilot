package com.pilot.server.common.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

/**
 * 接口错误响应的统一形状：{"message": "提示语"}。
 * 各控制器仍然自己声明哪种异常对应哪个状态码，这里只负责把响应体拼成同一个样子。
 */
public final class ApiErrors {
    /** 提示语字段名，前端按它取文案，不能改。 */
    public static final String MESSAGE = "message";

    /** 文件、文档类接口沿用的旧字段名：{"error": "提示语"}。前端按它取文案，不能和 message 混用。 */
    public static final String ERROR = "error";

    /** 异常自己没带提示语时补上这句，避免响应体拼不出来、接口变成 500。 */
    public static final String FALLBACK = "请求失败";

    private ApiErrors() {
    }

    /** 400：请求参数不合法。 */
    public static ResponseEntity<Map<String, String>> badRequest(Throwable failure) {
        return message(HttpStatus.BAD_REQUEST, failure.getMessage());
    }

    /** 409：数据已变化或当前状态不允许。 */
    public static ResponseEntity<Map<String, String>> conflict(Throwable failure) {
        return message(HttpStatus.CONFLICT, failure.getMessage());
    }

    /** 任意状态码加提示语。提示语为空时用 {@link #FALLBACK}，状态码仍是调用方声明的那个。 */
    public static ResponseEntity<Map<String, String>> message(HttpStatusCode status, String message) {
        return ResponseEntity.status(status).body(Map.of(MESSAGE, text(message)));
    }

    /** 旧字段名 error 的错误体；只给已经在用它的文件、文档类接口。 */
    public static ResponseEntity<Map<String, String>> error(HttpStatusCode status, String message) {
        return ResponseEntity.status(status).body(Map.of(ERROR, text(message)));
    }

    private static String text(String message) {
        return message == null ? FALLBACK : message;
    }
}
