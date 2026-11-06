package com.pilot.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.pilot.core.permission.PermissionMode;

class AgentPropertiesTest {
    @Test
    void directAndGoalDefaultsAreHighAccidentFusesRatherThanShortBusinessBudgets() {
        AgentProperties properties =
            new AgentProperties(".", PermissionMode.READ_ONLY, 120, 3, 5, 300, 4, 8, 120_000, true);

        assertEquals(120, properties.directMaxRequests());
        assertEquals(50_000_000L, properties.directMaxInputTokens());
        assertEquals(2_000_000L, properties.directMaxOutputTokens());
        assertEquals(28_800_000L, properties.directMaxActiveMillis());
        assertEquals(512, properties.directMaxInvestigationTools());
        assertEquals(50_000_000L, properties.goalMaxInputTokens());
        assertEquals(2_000_000L, properties.goalMaxOutputTokens());
        assertEquals(28_800_000L, properties.goalMaxActiveMillis());
    }

    @Test
    void defaultsRequireEvidenceBeforeCompleting() {
        AgentProperties properties = new AgentProperties(".", null, 0, null, null, 0, 0, null, null, null);

        assertEquals(PermissionMode.AUTO, properties.permissionMode(), "默认完全放行");
        assertEquals(1_000, properties.maxSteps());
        assertEquals(1_000, properties.directMaxRequests());
        assertEquals(400_000, properties.contextMaxChars());
        assertEquals(0, properties.maxPlanContinues(), "计划不再自动催继续");
        assertTrue(properties.completionChecksEnabled(), "收尾门默认开启");
    }
}
