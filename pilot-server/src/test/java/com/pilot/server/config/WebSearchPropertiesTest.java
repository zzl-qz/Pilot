package com.pilot.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.pilot.core.tool.search.WebSearchProvider;

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

    @Test
    void parsesMultiProviderFieldsAndMasksSecretsInToString() {
        var props = new WebSearchProperties(false, "http://127.0.0.1:8888", "", "", 15, "bocha",
            "sk-secret-main-key-1234", "https://api.bochaai.com/v1/web-search/", true, "http://127.0.0.1:7890",
            "bocha-key-9999", "tvly-key-8888", "", "", "");
        assertEquals(WebSearchProvider.BOCHA, props.searchProvider());
        assertEquals("https://api.bochaai.com/v1/web-search", props.apiEndpoint());
        assertTrue(props.fallbackEnabledDefault());
        assertEquals("bocha-key-9999", props.providerKeys().get(WebSearchProvider.BOCHA));
        assertEquals("tvly-key-8888", props.providerKeys().get(WebSearchProvider.TAVILY));
        String rendered = props.toString();
        assertFalse(rendered.contains("sk-secret-main-key-1234"));
        assertFalse(rendered.contains("bocha-key-9999"));
        assertFalse(rendered.contains("tvly-key-8888"));
    }

    @Test
    void rejectsInvalidProviderApiEndpointAndProxy() {
        assertThrows(IllegalArgumentException.class, () -> new WebSearchProperties(false, null, null, null, null,
            "unknown-provider", "", "", true, "", "", "", "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new WebSearchProperties(false, null, null, null, null,
            "bocha", "", "ftp://bad.host", true, "", "", "", "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new WebSearchProperties(false, null, null, null, null,
            "auto", "", "", true, "ftp://127.0.0.1:7890", "", "", "", "", ""));
    }
}
