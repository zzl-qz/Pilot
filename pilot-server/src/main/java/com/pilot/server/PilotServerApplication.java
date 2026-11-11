package com.pilot.server;

import java.util.Arrays;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.pilot.server.frontend.FrontendDepsPrompt;
import com.pilot.server.maintenance.MaintenanceCli;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class PilotServerApplication {
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("maintenance")) {
            int status = MaintenanceCli.run(Arrays.copyOfRange(args, 1, args.length), System.out, System.err);
            if (status != 0) {
                System.exit(status);
            }
            return;
        }
        // 开发态启动先问一句要不要更新前端依赖：IDE 直接运行 main 不经过 Maven，
        // pom 里 validate 阶段那次询问不会执行。打包后跑 jar 时这里静默跳过。
        FrontendDepsPrompt.askIfDevelopment();
        SpringApplication application = new SpringApplication(PilotServerApplication.class);
        application.addInitializers(MaintenanceCli::lockServer);
        application.run(args);
    }
}
