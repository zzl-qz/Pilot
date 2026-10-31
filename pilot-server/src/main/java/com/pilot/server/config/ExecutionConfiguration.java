package com.pilot.server.config;

import com.pilot.core.exec.ExecutionBackends;
import com.pilot.core.exec.ExecutionLimits;
import com.pilot.core.exec.ExecutionSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 命令执行隔离装配。
 *
 * <p>配置为 {@code container} 但容器不可用时，这里不会静默退回本地执行：
 * {@link ExecutionBackends.Selection#degraded()} 会保持 true，诊断接口与日志都会显示原因。
 */
@Configuration(proxyBeanMethods = false)
public class ExecutionConfiguration {

    @Bean
    ExecutionSettings executionSettings(@Value("${pilot.execution.isolation:local}") String isolation,
                                        @Value("${pilot.execution.image:}") String image,
                                        @Value("${pilot.execution.runtime:}") String runtime,
                                        @Value("${pilot.execution.network:false}") boolean network,
                                        @Value("${pilot.execution.command-network:true}") boolean commandNetwork,
                                        @Value("${pilot.execution.memory-mb:2048}") int memoryMb,
                                        @Value("${pilot.execution.cpus:2}") double cpus,
                                        @Value("${pilot.execution.pids-limit:512}") int pidsLimit,
                                        @Value("${pilot.multi.verify-timeout-seconds:600}") int timeoutSeconds) {
        if (System.getProperty("pilot.execution.command-network") == null) {
            System.setProperty("pilot.execution.command-network", Boolean.toString(commandNetwork));
        }
        ExecutionLimits limits = new ExecutionLimits(timeoutSeconds, 200_000,
                Math.max(64, memoryMb), Math.max(0.1, cpus), Math.max(8, pidsLimit));
        return new ExecutionSettings(com.pilot.core.exec.ExecutionIsolation.parse(isolation),
                image, runtime, network, limits);
    }

    @Bean
    ExecutionBackends.Selection executionSelection(ExecutionSettings settings) {
        return ExecutionBackends.select(settings);
    }
}
