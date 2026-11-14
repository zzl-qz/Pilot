package com.pilot.server.observability;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pilot.server.maintenance.RetentionService;

import lombok.RequiredArgsConstructor;

/** 进程内运行指标：运行成功率、失败码分布、压缩与工具调用次数。 */
@RestController
@RequestMapping("/api/metrics")
@RequiredArgsConstructor
public class MetricsController {
    private final RunMetrics metrics;
    private final RetentionService retention;

    @GetMapping
    public MetricsResponse metrics() {
        return new MetricsResponse(metrics.snapshot(), retention.retentionDays());
    }

    public record MetricsResponse(RunMetrics.Snapshot metrics, int retentionDays) {
    }
}
