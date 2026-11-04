package com.pilot.mobile.bridge

import java.nio.file.Files
import kotlin.test.*

class PairingStoreTest {
    @Test fun `配对单次消费重启恢复且撤销后拒绝`() {
        val directory = Files.createTempDirectory("pilot-pairing")
        try {
            val store = PairingStore(directory)
            val invite = store.issue("ticket", "电脑", emptyList(), false)
            assertFalse(store.pair("phone", "wrong", "手机"))
            assertTrue(store.pair("phone", invite.secret, "手机"))
            assertEquals("paired", store.pairingState())
            assertEquals("手机", store.pairedDevice()?.name)
            assertFalse(store.pair("other", invite.secret, "其他"))
            val restored = PairingStore(directory)
            assertContentEquals(store.key, restored.key)
            assertTrue(restored.isTrusted("phone"))
            restored.revoke("phone")
            assertFalse(PairingStore(directory).isTrusted("phone"))
        } finally { directory.toFile().deleteRecursively() }
    }
    @Test fun `重新生成立即使旧码失效且过期不可消费`() {
        val directory = Files.createTempDirectory("pilot-pairing")
        var now = 1000L
        try {
            val store = PairingStore(directory) { now }
            val first = store.issue("ticket", "电脑", emptyList(), false)
            val second = store.issue("ticket", "电脑", emptyList(), false)
            assertFalse(store.pair("phone", first.secret, "手机"))
            now += 300_000
            assertFalse(store.pair("phone", second.secret, "手机"))
            assertEquals("expired", store.pairingState())
            assertNull(store.currentInvite())
            assertFalse(store.isTrusted("phone"))
        } finally { directory.toFile().deleteRecursively() }
    }
    @Test fun `多设备授权独立且达到上限不会挤掉已有设备`() {
        val directory = Files.createTempDirectory("pilot-multiple-devices")
        try {
            val store = PairingStore(directory)
            repeat(PairingStore.MAX_DEVICES) { index ->
                val invite = store.issue("ticket", "电脑", emptyList(), false)
                assertNull(store.pairedDevice())
                assertTrue(store.pair("phone-$index", invite.secret, "手机 $index"))
            }
            assertEquals(16, store.devices().size)
            val excess = store.issue("ticket", "电脑", emptyList(), false)
            assertFalse(store.pair("phone-extra", excess.secret, "额外手机"))
            assertTrue(store.isTrusted("phone-0"))
            assertTrue(store.pair("phone-0", excess.secret, "重新配对的手机"))
            assertEquals(16, store.devices().size)
            store.revoke("phone-1")
            assertTrue(store.isTrusted("phone-0"))
            assertFalse(store.isTrusted("phone-1"))
            val next = store.issue("ticket", "电脑", emptyList(), false)
            assertTrue(store.pair("phone-extra", next.secret, "新手机"))
            store.revoke("phone-extra")
            assertNull(store.pairedDevice())
            assertEquals("none", store.pairingState())
            assertEquals(15, PairingStore(directory).devices().size)
        } finally { directory.toFile().deleteRecursively() }
    }

}
