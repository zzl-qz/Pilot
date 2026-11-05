package com.pilot.core.runtime.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BootstrapPlatformTest {
    @Test
    void normalizesPlatformsAndDoesNotMistakeDarwinForWindows() {
        assertEquals(new BootstrapPlatform("windows", "x64"), BootstrapPlatform.detect("Windows 11", "amd64"));
        assertEquals(new BootstrapPlatform("mac", "aarch64"), BootstrapPlatform.detect("Darwin", "arm64"));
        assertEquals(new BootstrapPlatform("mac", "x64"), BootstrapPlatform.detect("Mac OS X", "x86_64"));
        assertEquals(new BootstrapPlatform("linux", "aarch64"), BootstrapPlatform.detect("Linux", "aarch64"));
    }

    @Test
    void onlyAdvertisesPublishedNativeMobileArchitectures() {
        for (String os : new String[] {"windows", "mac", "linux"}) {
            for (String arch : new String[] {"x64", "aarch64", "unsupported"}) {
                var platform = new BootstrapPlatform(os, arch);
                boolean expected = !"unsupported".equals(arch) && ("linux".equals(os)
                    || "windows".equals(os) && "x64".equals(arch) || "mac".equals(os) && "aarch64".equals(arch));
                assertEquals(expected, platform.mobileSupported(), platform.key());
            }
        }
        assertFalse(BootstrapPlatform.detect("FreeBSD", "i386").mobileSupported());
    }

    @Test
    void emulationAndRosettaSelectCandidatesWithoutClaimingNativeSupport() {
        var armWindows = BootstrapPlatform.detect("Windows 11", "arm64");
        assertFalse(armWindows.mobileSupported());
        assertEquals(new BootstrapPlatform("windows", "x64"), armWindows.bridgeCandidate(false));
        var intelJvm = BootstrapPlatform.detect("Mac OS X", "x86_64");
        assertEquals(new BootstrapPlatform("mac", "aarch64"), intelJvm.bridgeCandidate(true));
        assertEquals(intelJvm, intelJvm.bridgeCandidate(false));
    }

    @Test
    void nodeRequiresTheDesktopMinimumVersionAndMatchingArchitecture() {
        var platform = new BootstrapPlatform("windows", "x64");
        assertFalse(SourceEnvironment.nodeVersionMatches("PILOT_NODE_RUNTIME 22.11.0 x64", platform));
        assertTrue(SourceEnvironment.nodeVersionMatches("PILOT_NODE_RUNTIME 22.12.0 x64", platform));
        assertTrue(SourceEnvironment.nodeVersionMatches("warning\nPILOT_NODE_RUNTIME 24.0.0 x64\n", platform));
        assertFalse(SourceEnvironment.nodeVersionMatches("PILOT_NODE_RUNTIME 22.23.4 arm64", platform));
        assertFalse(SourceEnvironment.nodeVersionMatches("unknown node", platform));
    }
}
