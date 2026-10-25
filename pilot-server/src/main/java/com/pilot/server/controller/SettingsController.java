package com.pilot.server.controller;

import com.pilot.core.permission.PermissionMode;
import com.pilot.server.config.PermissionSettings;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;

/**
 * 运行时设置：目前只有权限档位。
 *
 * <p>切换后立即对后续工具调用生效，不需要重启服务；进程重启会回到配置文件里的初始值。
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {
    private final PermissionSettings permissions;

    public SettingsController(PermissionSettings permissions) {
        this.permissions = permissions;
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

    private SettingsResponse current() {
        return new SettingsResponse(permissions.mode().name(),
                Arrays.stream(PermissionMode.values()).map(Enum::name).toList());
    }

    public record SettingsResponse(String permissionMode, List<String> permissionModes) { }

    public record PermissionRequest(String mode) { }
}
