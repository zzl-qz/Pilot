package com.pilot.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PilotServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(PilotServerApplication.class, args);
    }
}
