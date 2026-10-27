package com.pilot.server.controller;

import com.pilot.server.maintenance.RetentionService;
import com.pilot.server.observability.RunMetrics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 进程内运行指标：运行成功率、失败码分布、压缩与工具调用次数。 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {
    private final RunMetrics metrics;
    private final RetentionService retention;

    public MetricsController(RunMetrics metrics, RetentionService retention) {
        this.metrics = metrics;
        this.retention = retention;
    }

    @GetMapping
    public MetricsResponse metrics() {
        return new MetricsResponse(metrics.snapshot(), retention.retentionDays());
    }

    public record MetricsResponse(RunMetrics.Snapshot metrics, int retentionDays) {
    }
}
