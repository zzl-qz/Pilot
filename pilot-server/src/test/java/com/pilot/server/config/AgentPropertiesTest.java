package com.pilot.server.config;

import com.pilot.core.permission.PermissionMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentPropertiesTest {
    @Test void directAndGoalDefaultsAreHighAccidentFusesRatherThanShortBusinessBudgets() {
        AgentProperties properties = new AgentProperties(".", PermissionMode.READ_ONLY,
                120, 3, 5, 300, 4, 8, 120_000, true);

        assertEquals(120, properties.directMaxRequests());
        assertEquals(8_000_000L, properties.directMaxInputTokens());
        assertEquals(500_000L, properties.directMaxOutputTokens());
        assertEquals(7_200_000L, properties.directMaxActiveMillis());
        assertEquals(512, properties.directMaxInvestigationTools());
        assertEquals(8_000_000L, properties.goalMaxInputTokens());
        assertEquals(500_000L, properties.goalMaxOutputTokens());
        assertEquals(14_400_000L, properties.goalMaxActiveMillis());
    }
}
