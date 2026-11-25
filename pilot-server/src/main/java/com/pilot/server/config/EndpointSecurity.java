package com.pilot.server.config;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Shared endpoint origin and cloud metadata checks for outbound integrations. */
public final class EndpointSecurity {
    private static final Set<String> METADATA_HOSTS = Set.of("169.254.169.254", "169.254.170.2", "fd00:ec2::254",
        "metadata.google.internal", "metadata.goog", "metadata");

    private EndpointSecurity() {
    }

    public static boolean isCloudMetadataHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalized = host.strip().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return METADATA_HOSTS.contains(normalized) || normalized.startsWith("169.254.");
    }

    public static boolean isCloudMetadataUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return false;
        }
        try {
            return isCloudMetadataHost(URI.create(rawUrl.strip()).getHost());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public static boolean sameOrigin(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) {
            return false;
        }
        try {
            URI a = URI.create(left.strip());
            URI b = URI.create(right.strip());
            return a.getScheme() != null && b.getScheme() != null && a.getHost() != null && b.getHost() != null
                && a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost())
                && port(a) == port(b);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public static boolean sameOriginOrBothBlank(String left, String right) {
        boolean leftBlank = left == null || left.isBlank();
        boolean rightBlank = right == null || right.isBlank();
        if (leftBlank && rightBlank) {
            return true;
        }
        return !leftBlank && !rightBlank && sameOrigin(left, right);
    }

    private static int port(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : "http".equalsIgnoreCase(uri.getScheme()) ? 80 : -1;
    }
}
