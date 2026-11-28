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
        Protocol.validateRelays(emptyList())
        Protocol.validateRelays(listOf("https://relay-1.test", "https://relay-2.test", "https://relay-3.test", "https://relay-4.test"))
        for (invalid in listOf(
            listOf("https://a.test", "https://b.test", "https://c.test", "https://d.test", "https://e.test"),
            listOf("http://relay.test"),
            listOf("https://"),
            listOf("https://user:pass@relay.test"),
            listOf("https://relay.test/#frag"),
            listOf("https://bad host"),
        )) assertFailsWith<IllegalArgumentException> { Protocol.validateRelays(invalid) }
    }

    @Test fun `JsonObject 扩展安全提取文本布尔长整型和对象数组`() {
        val obj = Protocol.json.parseToJsonElement("""{"s":"hi","b":true,"f":false,"n":42,"bad":"abc","arr":[{"k":"v"},1,"x"]}""").jsonObject
        assertEquals("hi", obj.text("s"))
        assertEquals("", obj.text("missing"))
        assertTrue(obj.bool("b"))
        assertFalse(obj.bool("f"))
        assertFalse(obj.bool("arr"))
        assertEquals(42L, obj.long("n"))
        assertNull(obj.long("bad"))
        assertNull(obj.long("missing"))
        assertEquals(listOf("v"), obj.objects("arr").map { it.text("k") })
        assertTrue(obj.objects("s").isEmpty())
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

    @Test fun `历史版本变化清空旧状态并要求重放且控制消息不占游标`() {
        val transcript = Conversation()
        transcript.historyVersion(0)
        transcript.accept(Protocol.json.parseToJsonElement("""{"sequence":5,"type":"run.started","payload":{"input":"旧历史"}}""").jsonObject)
        transcript.accept(Protocol.json.parseToJsonElement("""{"type":"stream.reset","payload":{"historyRevision":1}}""").jsonObject)
        assertTrue(transcript.needsReplay)
        assertEquals(0, transcript.cursor)
        assertTrue(transcript.snapshot().isEmpty())
        assertEquals(1, transcript.historyRevision)
        transcript.reconnect()
        transcript.accept(Protocol.json.parseToJsonElement("""{"type":"stream.ready","payload":{"historyRevision":1}}""").jsonObject)
        assertFalse(transcript.needsReplay)
        assertEquals(0, transcript.cursor)
    }

    @Test fun `临时输出快照替换旧预览且偏移防止重复`() {
        val transcript = Conversation(); transcript.historyVersion(0)
        fun accept(value: String) = transcript.accept(Protocol.json.parseToJsonElement(value).jsonObject)
        accept("""{"sequence":1,"type":"run.started","runId":"r","payload":{"input":"任务"}}""")
        accept("""{"sequence":1,"type":"assistant.preview","runId":"r","payload":{"text":"旧"}}""")
        accept("""{"type":"stream.preview","payload":{"historyRevision":0,"baseSequence":1,"runId":"r","previewId":"p","previewOffset":2,"text":"头","reasoning":"思考","complete":true}}""")
        val live = """{"sequence":1,"type":"assistant.preview","runId":"r","previewId":"p","previewOffset":3,"payload":{"text":"尾"}}"""
        accept(live); accept(live)
        assertEquals("头尾", transcript.snapshot().last().text)
        assertEquals("思考", transcript.snapshot().first { it.role == "thinking" }.text)
        assertEquals(1, transcript.cursor)
        accept("""{"sequence":2,"type":"assistant.delta","runId":"r","payload":{"text":"头尾"}}""")
        assertEquals(2, transcript.snapshot().size)
        assertFailsWith<IllegalArgumentException> { accept("""{"type":"stream.preview","payload":{"historyRevision":0,"baseSequence":1}}""") }
    }

    @Test fun `思考和工具结果留在手机上，正文出来后思考收起`() {
        val transcript = Conversation()
        fun accept(value: String) = transcript.accept(Protocol.json.parseToJsonElement(value).jsonObject)
        accept("""{"sequence":1,"type":"run.started","runId":"r","payload":{"input":"改文件"}}""")
        accept("""{"type":"assistant.reasoning","payload":{"text":"先看入口"}}""")
        assertEquals("先看入口", transcript.snapshot().last { it.role == "thinking" }.text)
        accept("""{"sequence":2,"type":"tool.started","payload":{"name":"read_file","summary":"src/App.java"}}""")
        accept("""{"sequence":3,"type":"tool.completed","payload":{"resultSummary":"读到 main 方法"}}""")
        assertTrue(transcript.snapshot().any { it.role == "activity" && it.text.contains("read_file") })
        assertTrue(transcript.snapshot().any { it.role == "activity" && it.text.contains("读到 main 方法") })
        accept("""{"sequence":4,"type":"assistant.delta","payload":{"text":"已经看过入口。"}}""")
        assertTrue(transcript.snapshot().none { it.role == "thinking" })
    }

}
