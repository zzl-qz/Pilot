package com.pilot.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "pilot.store=memory")
class LiveStaticEnvironmentTest {
    @Autowired
    private Environment environment;

    @Test
    void staticFilesComeFromWebSources() {
        assertNotNull(LiveStaticEnvironment.findStaticSource());
        String locations = environment.getProperty(LiveStaticEnvironment.LOCATIONS_KEY);
        assertNotNull(locations);
        assertTrue(locations.contains("pilot-web/src/main/resources/static"), locations);
    }
}
