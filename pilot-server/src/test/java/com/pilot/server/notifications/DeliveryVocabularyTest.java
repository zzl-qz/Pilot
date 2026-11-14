package com.pilot.server.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/** 投递记录和接口里的字符串由枚举名决定，这里钉住名字。 */
class DeliveryVocabularyTest {
    @Test
    void namesStayEqualToPersistedStrings() {
        assertEquals(List.of("PENDING", "SENDING", "RETRY", "DELIVERED", "FAILED", "CANCELLED"),
            Arrays.stream(DeliveryState.values()).map(Enum::name).toList());
        assertEquals(List.of("DESKTOP", "WEBHOOK"), Arrays.stream(DeliveryChannel.values()).map(Enum::name).toList());
    }

    @Test
    void openAndRetryableSetsMatchTheOldLiteralSets() {
        assertTrue(DeliveryState.open("PENDING"));
        assertTrue(DeliveryState.open("RETRY"));
        assertTrue(DeliveryState.open("SENDING"));
        assertFalse(DeliveryState.open("DELIVERED"));
        assertFalse(DeliveryState.open("FAILED"));
        assertFalse(DeliveryState.open("CANCELLED"));
        assertFalse(DeliveryState.open(null));

        assertTrue(DeliveryState.retryable("FAILED"));
        assertTrue(DeliveryState.retryable("RETRY"));
        assertFalse(DeliveryState.retryable("PENDING"));
        assertFalse(DeliveryState.retryable("SENDING"));
    }
}
