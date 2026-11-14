package com.pilot.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class WireEnumTest {
    private enum Light implements WireEnum {
        RED, GREEN
    }

    @Test
    void comparesAgainstWireStringsWithoutThrowing() {
        assertTrue(Light.RED.is("RED"));
        assertFalse(Light.RED.is("red"));
        assertFalse(Light.RED.is(null));
        assertTrue(WireEnum.in("GREEN", Light.RED, Light.GREEN));
        assertFalse(WireEnum.in("BLUE", Light.values()));
        assertFalse(WireEnum.in(null, Light.values()));
    }

    @Test
    void parsesKnownValuesAndKeepsUnknownOnesEmpty() {
        assertEquals(Optional.of(Light.GREEN), WireEnum.parse(Light.class, "GREEN"));
        assertEquals(Optional.empty(), WireEnum.parse(Light.class, "BLUE"));
        assertEquals(Optional.empty(), WireEnum.parse(Light.class, null));
    }
}
