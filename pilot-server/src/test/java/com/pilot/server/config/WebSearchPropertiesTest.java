package com.pilot.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class WebSearchPropertiesTest {
    @Test
    void managedEndpointSuppliesTheSameAddressToLauncherAndClient() {
        var defaults = new WebSearchProperties(true, null, null, null);
        assertEquals("http://127.0.0.1:8888", defaults.endpoint());
        assertEquals(Duration.ofSeconds(20), defaults.requestTimeout());

        var localhost = new WebSearchProperties(true, " http://localhost/ ", null, null);
        assertEquals("127.0.0.1", localhost.managedHost());
        assertEquals("http://localhost:8888", localhost.endpoint());

        var ipv6 = new WebSearchProperties(true, "http://[::1]:9288/", null, null, 5);
        assertEquals("::1", ipv6.managedHost());
        assertEquals(9288, ipv6.endpointUri().getPort());
        assertEquals(Duration.ofSeconds(5), ipv6.requestTimeout());
    }

    @Test
    void externalEndpointsKeepTheirPathAndImplicitHttpPort() {
        var proxy = new WebSearchProperties(true, "https://localhost/search-proxy/", null, null);
        assertNull(proxy.managedHost());
        assertEquals("https://localhost/search-proxy", proxy.endpoint());

        var external = new WebSearchProperties(false, "http://localhost", null, null);
        assertNull(external.managedHost());
        assertEquals(-1, external.endpointUri().getPort());
    }

    @Test
    void rejectsInvalidEndpointAndTimeout() {
        for (String endpoint : new String[] {"file:///tmp/search", "http://", "https://host?query=x",
            "https://user:password@host", "http://host:0"}) {
            assertThrows(IllegalArgumentException.class, () -> new WebSearchProperties(true, endpoint, null, null));
        }
        assertThrows(IllegalArgumentException.class, () -> new WebSearchProperties(false, null, null, null, 0));
    }
}
