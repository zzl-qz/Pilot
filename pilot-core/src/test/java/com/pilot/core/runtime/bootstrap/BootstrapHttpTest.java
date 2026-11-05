package com.pilot.core.runtime.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.Proxy;
import java.net.URI;
import java.util.Map;

import org.junit.jupiter.api.Test;

class BootstrapHttpTest {
    @Test
    void configuredProxyHonorsHostAndDomainExclusions() {
        var http = BootstrapHttp
            .client(Map.of("https_proxy", "http://127.0.0.1:18080", "no_proxy", "localhost,.example.test"));
        var selector = http.proxy().orElseThrow();
        assertEquals(Proxy.Type.HTTP, selector.select(URI.create("https://nodejs.org")).getFirst().type());
        assertEquals(Proxy.Type.DIRECT, selector.select(URI.create("https://localhost")).getFirst().type());
        assertEquals(Proxy.Type.DIRECT, selector.select(URI.create("https://api.example.test")).getFirst().type());
        assertFalse(BootstrapHttp.bypass("evil-example.test", ".example.test"));
    }

    @Test
    void invalidProxyDoesNotEchoEmbeddedCredentials() {
        var failure = assertThrows(IllegalArgumentException.class,
            () -> BootstrapHttp.client(Map.of("HTTPS_PROXY", "socks://user:secret@localhost:8080")));
        assertFalse(failure.getMessage().contains("secret"));
    }
}
