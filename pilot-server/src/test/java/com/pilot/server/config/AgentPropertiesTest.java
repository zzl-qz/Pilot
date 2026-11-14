package com.pilot.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

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

    @Test
    void directDefaultsKeepTheEstablishedCeilingWhenMaximumStepsAreIncreased() {
        var defaults = new AgentProperties(".", null, 1500, null, null, 0, 0, null, null, true);
        assertEquals(1_500, defaults.maxSteps());
        assertEquals(1_000, defaults.directMaxRequests());

        var configured = new AgentProperties(".", null, 1500, 0, 5, 90, 20, null, 50_000, true, true, 1250, 70_000L,
            7_000L, 70_000L, 30, 20_000L, 2_000L, 80_000L);
        assertEquals(1_250, configured.directMaxRequests());
        assertEquals(0, configured.repeatRemindAt());
        assertEquals(90, configured.approvalTimeoutSeconds());
        assertEquals(16, configured.runThreads());
        assertEquals(70_000L, configured.directMaxInputTokens());
        assertEquals(80_000L, configured.goalMaxActiveMillis());
        assertThrows(IllegalArgumentException.class,
            () -> new AgentProperties(".", null, 1, 5, 5, 0, 0, null, null, true));
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void explicitCompletionSettingTakesPrecedenceOverLegacySystemProperty() {
        String key = "pilot.completion.checks";
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, "false");
            var inherited = new AgentProperties(".", null, 0, null, null, 0, 0, null, null, null);
            var explicit = new AgentProperties(".", null, 0, null, null, 0, 0, null, null, true);
            assertFalse(inherited.completionChecksEnabled());
            assertTrue(explicit.completionChecksEnabled());
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }
}
