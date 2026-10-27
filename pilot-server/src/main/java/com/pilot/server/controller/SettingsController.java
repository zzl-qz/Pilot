package com.pilot.server.controller;

import com.pilot.core.permission.PermissionMode;
import com.pilot.server.config.ModelSettings;
import com.pilot.server.config.PermissionSettings;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;

/**
 * 运行时设置：权限档位与模型配置。
 *
 * <p>两者都立即对后续请求生效并落盘，不需要重启服务；
 * API Key 只返回「是否已配置」和掩码，不回传明文。
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {
    private final PermissionSettings permissions;
    private final ModelSettings models;

    public SettingsController(PermissionSettings permissions, ModelSettings models) {
        this.permissions = permissions;
        this.models = models;
    }

    @GetMapping
    public SettingsResponse get() {
        return current();
    }

    @PutMapping("/permission-mode")
    public SettingsResponse updatePermissionMode(@RequestBody(required = false) PermissionRequest request) {
        String raw = request == null ? null : request.mode();
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode must not be blank");
        }
        try {
            permissions.update(PermissionMode.valueOf(raw.strip().toUpperCase()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "未知权限档位: " + raw + "，可选: " + Arrays.toString(PermissionMode.values()));
        }
        return current();
    }

    /**
     * 修改模型配置：字段留空表示保持原值，因此只改模型名时不必重填端点与 Key。
     * 保存后下一次模型调用即生效。
     */
    @PutMapping("/model")
    public SettingsResponse updateModel(@RequestBody(required = false) ModelRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        try {
            models.update(request.endpoint(), request.model(), request.apiKey(),
                    Boolean.TRUE.equals(request.clearApiKey()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return current();
    }

    /**
     * 明确清除已保存的 API Key（幂等）。
     *
     * <p>与 PUT 里「apiKey 留空表示保持原值」区分开：清除是一个不会被误解的独立动作。
     * 清除结果会落盘并覆盖配置文件/环境变量里的默认值，重启后也不会自动回填。
     */
    @DeleteMapping("/model/api-key")
    public SettingsResponse clearModelApiKey() {
        models.update(null, null, null, true);
        return current();
    }

    private SettingsResponse current() {
        ModelSettings.Snapshot snapshot = models.snapshot();
        return new SettingsResponse(
                permissions.mode().name(),
                Arrays.stream(PermissionMode.values()).map(Enum::name).toList(),
                snapshot.model(),
                snapshot.endpoint(),
                snapshot.hasApiKey(),
                snapshot.maskedApiKey(),
                models.options());
    }

    public record SettingsResponse(String permissionMode, List<String> permissionModes,
                                   String model, String modelEndpoint,
                                   boolean modelApiKeySet, String modelApiKeyMasked,
                                   List<String> modelOptions) { }

    public record PermissionRequest(String mode) { }

    /**
     * Jackson 3 对「缺失字段 + 基本类型」会按 null 处理并报错，
     * 所以 {@code clearApiKey} 用包装类型，缺省视为不清理。
     */
    public record ModelRequest(String endpoint, String model, String apiKey, Boolean clearApiKey) { }
}
