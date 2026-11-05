package com.pilot.mobile.transport

import computer.iroh.*
import kotlin.test.*

class DirectRoutingTest {
    @Test fun `没有配置时剥离旧票据的公共中继并保留电脑身份和直连地址`() {
        SecretKey.generate().use { key ->
            key.public().use { id ->
                EndpointAddr(id, "https://old-relay.example.com/", listOf("127.0.0.1:12345")).use { original ->
                    EndpointTicket.fromAddr(original).use { ticket ->
                        dialAddress(ticket.toString(), emptyList()).use { target ->
                            assertNull(target.relayUrl())
                            assertEquals(original.id(), target.id())
                            assertEquals(original.directAddresses(), target.directAddresses())
                        }
                        dialAddress(ticket.toString(), listOf("https://old-relay.example.com/")).use { configured ->
                            assertNotNull(configured.relayUrl())
                            assertEquals(original.id(), configured.id())
                        }
                        dialAddress(ticket.toString(), emptyList(), allowPublicRelays = true).use { automatic ->
                            assertEquals(original.relayUrl()?.toString(), automatic.relayUrl()?.toString())
                            assertEquals(original.id(), automatic.id())
                            assertEquals(original.directAddresses(), automatic.directAddresses())
                        }
                    }
                }
            }
        }
    }
}
