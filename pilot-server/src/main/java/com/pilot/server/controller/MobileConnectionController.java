package com.pilot.server.controller;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.server.mobile.MobileConnectionService;
import com.pilot.server.security.AccessControl;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** 本机管理入口。远程配对手机不能转授其他设备；禁止跨来源网页创建配对凭证。 */
@RestController
@RequestMapping("/api/mobile/connection")
public class MobileConnectionController {
    private final MobileConnectionService service;

    public MobileConnectionController(MobileConnectionService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<JsonNode> status(HttpServletRequest request) {
        return call(request, "status", null);
    }

    @PostMapping("/pair")
    public ResponseEntity<JsonNode> pair(HttpServletRequest request, @RequestBody(required = false) JsonNode body) {
        return call(request, "pair", body);
    }

    @PostMapping("/repair")
    public ResponseEntity<JsonNode> repair(HttpServletRequest request) {
        return call(request, "repair", null);
    }

    @PostMapping("/revoke")
    public ResponseEntity<JsonNode> revoke(HttpServletRequest request, @RequestBody JsonNode body) {
        if (!body.path("id").asText("").matches("[A-Za-z0-9_-]{20,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备标识无效");
        }
        return call(request, "revoke", body);
    }

    private ResponseEntity<JsonNode> call(HttpServletRequest request, String operation, JsonNode body) {
        if (!AccessControl.isLoopback(request.getRemoteAddr())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请在运行 Pilot 的电脑上管理配对设备");
        }
        String origin = request.getHeader("Origin");
        try {
            URI host = URI.create(request.getRequestURL().toString());
            if (!"localhost".equals(host.getHost()) && !AccessControl.isLoopback(host.getHost())) {
                throw new IllegalArgumentException("host");
            }
            if (origin != null && !origin.equals(host.getScheme() + "://" + host.getRawAuthority())) {
                throw new IllegalArgumentException("origin");
            }
            if ("cross-site".equals(request.getHeader("Sec-Fetch-Site"))) {
                throw new IllegalArgumentException("site");
            }
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请从 Pilot 工作台管理设备");
        }
        try {
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.request(operation, body));
        } catch (IllegalStateException e) {
            // 默认 Spring 错误页可能不包含异常原因；本机管理接口显式返回经过服务层控制的提示。
            JsonNode failure = JsonNodeFactory.instance.objectNode().put("code", "MOBILE_CONNECTION_UNAVAILABLE")
                .put("message", e.getMessage()).put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Cache-Control", "no-store")
                .body(failure);
        }
    }
}
