package com.pilot.mobile.transport

import kotlinx.serialization.json.*
import kotlin.test.*

class ReceiptProtocolTest {
    private val id = "r3.1800000000000.12345678-1234-1234-1234-123456789abc." + "a".repeat(64)
    @Test fun `新标识必须由支持协议的电脑签发`() {
        assertEquals(id, ReceiptProtocol.issuedId(ResponseHead(200) to """{"requestId":"$id","receiptVersion":3}"""))
        for (response in listOf(ResponseHead(404) to "{}", ResponseHead(200) to "{}", ResponseHead(200) to """{"requestId":"old","receiptVersion":3}""")) {
            assertFailsWith<IllegalArgumentException> { ReceiptProtocol.issuedId(response) }
        }
        assertTrue(ReceiptProtocol.validId(id))
        assertTrue(ReceiptProtocol.validId("12345678-1234-1234-1234-123456789abc"))
        assertFalse(ReceiptProtocol.validId("../receipt"))
    }
    @Test fun `过期拒绝授权升级限流和未知结果保留待核对记录`() {
        val success = buildJsonObject { put("accepted", true) }
        for (status in listOf(401, 403, 408, 410, 426, 429, 500, 503, 507)) assertFalse(ReceiptProtocol.confirmable(status, success))
        assertFalse(ReceiptProtocol.confirmable(409, buildJsonObject { put("uncertain", true) }))
        assertFalse(ReceiptProtocol.confirmable(200, null))
        assertTrue(ReceiptProtocol.confirmable(200, success))
        assertTrue(ReceiptProtocol.confirmable(409, buildJsonObject { put("message", "队列版本冲突") }))
        val ack = ReceiptProtocol.acknowledge(id)
        assertNull(ack.requestId) // 确认不是新的业务写请求，不递归产生回执。
        assertEquals(id, Protocol.json.parseToJsonElement(ack.body).jsonObject["requestId"]!!.jsonPrimitive.content)
    }
}
