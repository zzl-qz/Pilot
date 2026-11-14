package com.pilot.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.pilot.core.exec.ExecutionBackends;
import com.pilot.core.exec.ExecutionIsolation;
import com.pilot.core.exec.ExecutionLimits;
import com.pilot.core.exec.ExecutionSettings;

/**
 * 命令执行隔离装配。
 *
 * <p>配置为 {@code container} 但容器不可用时，这里不会静默退回本地执行：
 * {@link ExecutionBackends.Selection#degraded()} 会保持 true，诊断接口与日志都会显示原因。
 */
@Configuration(proxyBeanMethods = false)
public class ExecutionConfiguration {

    @Bean
    ExecutionSettings executionSettings(PilotDataDir dataDir,
        @Value("${pilot.execution.isolation:local}") String isolation,
        @Value("${pilot.execution.image:" + ExecutionSettings.DEFAULT_CONTAINER_IMAGE + "}") String image,
        @Value("${pilot.execution.runtime:}") String runtime,
        @Value("${pilot.execution.network:false}") boolean network,
        @Value("${pilot.execution.command-network:true}") boolean commandNetwork,
        @Value("${pilot.execution.memory-mb:" + ExecutionLimits.DEFAULT_MEMORY_MB + "}") int memoryMb,
        @Value("${pilot.execution.cpus:" + ExecutionLimits.DEFAULT_CPUS + "}") double cpus,
        @Value("${pilot.execution.pids-limit:" + ExecutionLimits.DEFAULT_PIDS + "}") int pidsLimit,
        @Value("${pilot.multi.verify-timeout-seconds:" + ExecutionLimits.DEFAULT_TIMEOUT_SECONDS
            + "}") int timeoutSeconds) {
        if (System.getProperty("pilot.execution.command-network") == null) {
            System.setProperty("pilot.execution.command-network", Boolean.toString(commandNetwork));
        }
        var limits = new ExecutionLimits(timeoutSeconds, ExecutionLimits.defaults().maxOutputBytes(),
            Math.max(ExecutionLimits.MIN_MEMORY_MB, memoryMb), Math.max(0.1, cpus),
            Math.max(ExecutionLimits.MIN_PIDS, pidsLimit));
        return new ExecutionSettings(ExecutionIsolation.parse(isolation), image, runtime, network, limits,
            dataDir.file("build-cache"));
    }

    @Bean
    ExecutionBackends.Selection executionSelection(ExecutionSettings settings) {
        return ExecutionBackends.select(settings);
    }
}
