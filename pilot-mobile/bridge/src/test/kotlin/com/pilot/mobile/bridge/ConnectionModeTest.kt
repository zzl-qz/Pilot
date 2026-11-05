package com.pilot.mobile.bridge

import com.pilot.mobile.transport.*
import computer.iroh.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class ConnectionModeTest {
    @Test fun `默认和旧开发标记均不会启用中继`(): Unit = runBlocking {
        val first = createEndpoint(SecretKey.generate().toBytes())
        val legacy = createEndpoint(SecretKey.generate().toBytes(), development = true)
        try {
            first.addr().use { assertNull(it.relayUrl()) }
            legacy.addr().use { assertNull(it.relayUrl()) }
            val key = SecretKey.generate().toBytes()
            assertFails { createEndpoint(key, listOf("not-a-relay")) }
        } finally { first.shutdown(); legacy.shutdown() }
    }
}
