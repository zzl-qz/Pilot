package com.pilot.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 三个平台的真实 os.name 都要认对，且互不串台。 */
class HostOsTest {

    @Test
    void recognisesRealOsNames() {
        for (String name : new String[] {"Windows 11", "Windows 10", "Windows Server 2022", "windows 7"}) {
            assertTrue(HostOs.isWindows(name), name);
            assertFalse(HostOs.isMac(name), name);
            assertFalse(HostOs.isLinux(name), name);
        }
        assertTrue(HostOs.isMac("Mac OS X"));
        assertFalse(HostOs.isWindows("Mac OS X"));
        assertFalse(HostOs.isLinux("Mac OS X"));
        assertTrue(HostOs.isLinux("Linux"));
        assertFalse(HostOs.isWindows("Linux"));
        assertFalse(HostOs.isMac("Linux"));
    }

    @Test
    void darwinIsMacAndNeverWindows() {
        assertTrue(HostOs.isMac("Darwin"));
        assertFalse(HostOs.isWindows("Darwin"));
    }

    @Test
    void blankOrUnknownNamesMatchNothing() {
        for (String name : new String[] {null, "", "FreeBSD"}) {
            assertFalse(HostOs.isWindows(name));
            assertFalse(HostOs.isMac(name));
            assertFalse(HostOs.isLinux(name));
        }
    }

    @Test
    void currentHostIsExactlyOneFamilyOrNone() {
        int matched = (HostOs.isWindows() ? 1 : 0) + (HostOs.isMac() ? 1 : 0) + (HostOs.isLinux() ? 1 : 0);
        assertTrue(matched <= 1);
        assertEquals(System.getProperty("os.name", ""), HostOs.name());
    }
}
