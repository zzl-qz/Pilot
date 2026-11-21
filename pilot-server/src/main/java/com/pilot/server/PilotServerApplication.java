package com.pilot.server;

import java.util.Arrays;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.pilot.server.frontend.FrontendStartup;
import com.pilot.server.maintenance.backup.MaintenanceCli;

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
        FrontendStartup.prepare();
        SpringApplication application = new SpringApplication(PilotServerApplication.class);
        application.addInitializers(MaintenanceCli::lockServer);
        application.run(args);
    }
}
