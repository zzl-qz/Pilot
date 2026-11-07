package com.pilot.mobile.transport

import kotlin.test.*
import kotlinx.serialization.json.jsonObject

class ProtocolTest {
    @Test fun `旧请求默认明文且新增元数据不改变协议版本`() {
        val legacy = Protocol.json.decodeFromString(Request.serializer(), """{"method":"GET","path":"/api/mobile/workbench"}""")
        assertFalse(legacy.acceptGzipFrames)
        assertNull(legacy.snapshotRevision)
        assertEquals("", Protocol.json.decodeFromString(ResponseHead.serializer(), """{"status":200,"contentType":"application/json"}""").frameEncoding)
        assertEquals(2, Protocol.VERSION)
    }

    private val now = 1_800_000_000_000L
    private fun invite() = PairingInvite(ticket = "a".repeat(100), secret = "a".repeat(43), expiresAt = now + 300_000, name = "工作电脑")

    @Test fun `只读超时与写入结果未确认使用不同提示`() {
        assertContains(requestTimeoutMessage("GET"), "读取数据超时")
        assertFalse(requestTimeoutMessage("GET").contains("指令"))
        for (method in listOf("POST", "PUT", "PATCH", "DELETE")) assertContains(requestTimeoutMessage(method), "核对")
    }

    @Test fun `配对码保留中文名称且有版本与过期检查`() {
        assertEquals(invite(), PairingInvite.decode(invite().encode(), now))
        assertFailsWith<IllegalArgumentException> { PairingInvite.decode(invite().encode(), now + 300_000) }
        assertFailsWith<IllegalArgumentException> { PairingInvite.decode(invite().copy(version = 3).encode(), now) }
        assertFailsWith<IllegalArgumentException> { PairingInvite.decode("https://example.com", now) }
        assertFailsWith<IllegalArgumentException> { PairingInvite.decode("pilot://pair/broken", now) }
    }
    @Test fun `拒绝过大票据与不安全的中继配置`() {
        assertFailsWith<IllegalArgumentException> { PairingInvite.decode(invite().copy(ticket = "x".repeat(5000)).encode(), now) }
        assertFailsWith<IllegalArgumentException> { PairingInvite.decode(invite().copy(relays = listOf("http://relay.test")).encode(), now) }
        assertFailsWith<IllegalArgumentException> { PairingInvite.decode(invite().copy(secret = "short").encode(), now) }
    }
    @Test fun `SSE 支持中文跨字节分片多行和心跳`() {
        val events = mutableListOf<String>()
        val decoder = SseDecoder { events += it.text("text") }
        val raw = ":ping\r\n\r\nevent: assistant.delta\r\ndata: {\"text\":\r\ndata: \"中文回复\"}\r\n\r\n".toByteArray()
        raw.forEach { decoder.feed(byteArrayOf(it)) }
        assertEquals(listOf("中文回复"), events)
        assertFailsWith<IllegalArgumentException> { decoder.feed(ByteArray(Protocol.MAX_FRAME + 1) { 65 }) }
    }
    @Test fun `正文提交替换预览且持久事件重放不重复`() {
        val transcript = Conversation()
        fun event(seq: Int, type: String, payload: String) = Protocol.json.parseToJsonElement("""{"sequence":$seq,"type":"$type","runId":"r1","payload":$payload}""").jsonObject
        transcript.accept(event(1, "run.started", """{"input":"检查代码"}"""))
        transcript.accept(event(2, "assistant.preview", """{"text":"我正在"}"""))
        assertEquals(1, transcript.cursor)
        // 请求重试或输出被判为复读：旧预览作废，不能和重试后的内容拼成两段。
        transcript.accept(event(2, "assistant.preview", """{"reset":true}"""))
        assertEquals(1, transcript.snapshot().size)
        transcript.accept(event(2, "assistant.preview", """{"text":"我正在"}"""))
        transcript.accept(event(2, "assistant.delta", """{"text":"我正在检查。"}"""))
        transcript.accept(event(2, "assistant.delta", """{"text":"我正在检查。"}"""))
        assertEquals(2, transcript.snapshot().size)
        assertEquals("我正在检查。", transcript.snapshot().last().text)
        transcript.accept(event(3, "run.completed", "{}"))
        assertEquals("", transcript.runId)
    }
    @Test fun `长历史分页可扩展行数但字符内存保持有界`() {
        val transcript = Conversation(6000)
        for (sequence in 1..500) transcript.accept(Protocol.json.parseToJsonElement("""{"sequence":$sequence,"type":"assistant.delta","payload":{"text":"历史 $sequence"}}""").jsonObject)
        assertEquals(500, transcript.snapshot().size)
        val body = "x".repeat(128_001)
        for (sequence in 501..600) transcript.accept(Protocol.json.parseToJsonElement("""{"sequence":$sequence,"type":"assistant.delta","payload":{"text":"$body"}}""").jsonObject)
        assertTrue(transcript.snapshot().sumOf { it.text.length } <= 4_000_000)
        assertTrue(transcript.snapshot().last().text.contains("完整内容请在电脑端查看"))
        assertEquals(600, transcript.cursor)
    }

}
