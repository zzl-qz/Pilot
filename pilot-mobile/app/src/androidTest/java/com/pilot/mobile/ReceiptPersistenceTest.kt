package com.pilot.mobile

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class ReceiptPersistenceTest {
    @Test fun completedResultAndAcknowledgmentQueueSurviveStoreRecreationWithoutClearingOtherHosts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SecureDeviceStore(context)
        val host = "receipt-test"
        val other = "receipt-test-other"
        try {
            store.value(host, "pending", "original")
            store.value(other, "pending", "other-operation")
            store.completeRequest(host, "request-1", "{\"accepted\":true}")
            store.completeRequest(host, "request-2", "{\"accepted\":true}")
            val restored = SecureDeviceStore(context)
            assertNull(restored.value(host, "pending"))
            assertEquals("{\"accepted\":true}", restored.value(host, "receipt-result"))
            assertEquals(listOf("request-1", "request-2"), restored.acknowledgments(host))
            assertEquals("other-operation", restored.value(other, "pending"))
            restored.acknowledged(host, "request-1")
            restored.acknowledged(host, "request-1")
            assertEquals(listOf("request-2"), SecureDeviceStore(context).acknowledgments(host))
        } finally {
            for (key in listOf("pending", "receipt-result", "receipt-acks")) {
                store.value(host, key, null); store.value(other, key, null)
            }
        }
    }
}
