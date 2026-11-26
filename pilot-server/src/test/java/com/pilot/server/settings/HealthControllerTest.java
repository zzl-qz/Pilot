package com.pilot.server.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;

import com.pilot.core.permission.PermissionMode;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.model.ModelSettings;
import com.pilot.server.permission.PermissionSettings;

class HealthControllerTest {
    @Test
    void hidesWorkspaceFromNonLoopbackRequestsAndKeepsLoopbackBehavior() {
        AgentProperties agent = mock(AgentProperties.class);
        ModelSettings model = mock(ModelSettings.class);
        PermissionSettings permission = mock(PermissionSettings.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ToolRegistry> registry = mock(ObjectProvider.class);
        when(agent.workspace()).thenReturn("/private/workspace");
        when(model.snapshot()).thenReturn(new ModelSettings.Snapshot("", "model", ""));
        when(permission.mode()).thenReturn(PermissionMode.AUTO);
        HealthController controller = new HealthController(agent, model, permission, registry);

        MockHttpServletRequest remote = new MockHttpServletRequest();
        remote.setRemoteAddr("192.0.2.10");
        assertEquals("", controller.health(remote).workspace());
        assertEquals("/private/workspace", controller.health(null).workspace());
        remote.setRemoteAddr("127.0.0.2");
        assertEquals("/private/workspace", controller.health(remote).workspace());
        remote.setRemoteAddr("::1");
        assertEquals("/private/workspace", controller.health(remote).workspace());
    }
}
