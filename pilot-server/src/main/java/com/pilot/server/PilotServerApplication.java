package com.pilot.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.pilot.server.frontend.FrontendDepsPrompt;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class PilotServerApplication {
    public static void main(String[] args) {
        // 开发态启动先问一句要不要更新前端依赖：IDE 直接运行 main 不经过 Maven，
        // pom 里 validate 阶段那次询问不会执行。打包后跑 jar 时这里静默跳过。
        FrontendDepsPrompt.askIfDevelopment();
        SpringApplication.run(PilotServerApplication.class, args);
    }
}
