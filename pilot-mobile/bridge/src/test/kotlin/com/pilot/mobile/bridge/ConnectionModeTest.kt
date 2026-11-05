package com.pilot.mobile.bridge

import com.pilot.mobile.transport.*
import computer.iroh.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class ConnectionModeTest {
    @Test fun `显式直连模式不启用中继且拒绝无效中继配置`(): Unit = runBlocking {
        val first = createEndpoint(SecretKey.generate().toBytes(), development = false)
        try {
            first.addr().use { assertNull(it.relayUrl()) }
            val key = SecretKey.generate().toBytes()
            assertFails { createEndpoint(key, listOf("not-a-relay")) }
        } finally { first.shutdown() }
    }
}
