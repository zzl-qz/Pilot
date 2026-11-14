package com.pilot.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

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

    @Test
    void configuredStaticLocationsTakePrecedenceOverSourceFiles() {
        for (String key : new String[] {LiveStaticEnvironment.LOCATIONS_KEY,
            LiveStaticEnvironment.LOCATIONS_KEY + "[0]"}) {
            var configured = new MockEnvironment().withProperty(key, "classpath:/custom/");
            new LiveStaticEnvironment().postProcessEnvironment(configured, null);
            assertEquals("classpath:/custom/", configured.getProperty(key));
            assertFalse(configured.getPropertySources().contains("pilotLiveStatic"));
        }
    }
}
