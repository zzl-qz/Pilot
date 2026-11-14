package com.pilot.server.session;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 用量聚合端点：设置页成本面板的数据源。
 *
 * <p>GET /api/usage?days=30 —— 日序列 + 会话 Top + 合计；主 Agent 与已归集的
 * 子 Agent（spawn / dispatch）用量都在内，中断 run 的已发生成本也计入。
 */
@RestController
@RequiredArgsConstructor
public class UsageController {

    private final UsageService usage;

    @GetMapping("/api/usage")
    public UsageService.UsageReport usage(@RequestParam(defaultValue = "30") int days) {
        return usage.report(days);
    }
}
