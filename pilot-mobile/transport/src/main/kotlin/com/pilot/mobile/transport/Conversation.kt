package com.pilot.mobile.transport

import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream

fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: ""

data class TranscriptRow(val key: String, val role: String, val text: String)

/** 持久事件去重；预览与最终正文分开，续接不会把一次回复重复拼接。 */
class Conversation(private val maxRows: Int = 300) {
    var cursor: Long = 0; private set
    var historyRevision: Long? = null; private set
    var needsReplay = false; private set
    private val rows = mutableListOf<TranscriptRow>()
    private var preview = ""
    private var rowChars = 0
    var runId = ""; private set
    var phase = ""; private set
    fun snapshot(): List<TranscriptRow> = rows.toList() + if (preview.isNotEmpty()) listOf(TranscriptRow("preview", "assistant", preview)) else emptyList()
    fun reconnect() { preview = ""; needsReplay = false }

    fun historyVersion(revision: Long) { historyRevision = revision }

    fun accept(event: JsonObject) {
        val type = event.text("type")
        val payload = event["payload"] as? JsonObject ?: return
        if (type == "stream.ready" || type == "stream.reset") {
            val revision = payload.text("historyRevision").toLongOrNull() ?: error("历史版本无效")
            if (type == "stream.reset" || historyRevision != null && historyRevision != revision) {
                rows.clear(); rowChars = 0; preview = ""; cursor = 0; runId = ""; phase = "重新加载历史"
                needsReplay = true
            }
            historyRevision = revision
            return
        }
        if (needsReplay || type.startsWith("stream.")) return
        val ephemeral = type in setOf("assistant.preview", "assistant.reasoning")
        val sequence = event.text("sequence").toLongOrNull() ?: return
        if (!ephemeral && sequence <= cursor) return
        val id = event.text("runId")
        when (type) {
            "run.started" -> {
                runId = id; phase = "正在执行"; preview = ""
                val input = payload.text("input")
                if (rows.lastOrNull()?.let { it.role == "user" && it.text == input } != true) add(sequence, "user", input)
            }
            "user.message" -> add(sequence, "user", payload.text("input").ifBlank { payload.text("text") })
            "assistant.preview" -> {
                // reset：请求重试或输出被判为复读，上一次尝试的片段作废。
                val reset = (payload["reset"] as? JsonPrimitive)?.booleanOrNull == true
                if (reset) preview = ""
                else if (id == runId || runId.isBlank()) preview = (preview + payload.text("text")).takeLast(128_000)
            }
            "assistant.delta" -> { preview = ""; add(sequence, "assistant", payload.text("text")) }
            "tool.started" -> { phase = "正在调用 ${payload.text("name")}"; add(sequence, "activity", phase + "\n" + payload.text("summary")) }
            "tool.completed" -> phase = "正在处理结果"
            "approval.required" -> phase = "等待你的确认"
            "run.steered" -> add(sequence, "user", payload.text("content").ifBlank { payload.text("input") })
            "run.continued" -> { runId = id; phase = "正在继续" }
            "run.failed" -> { preview = ""; runId = ""; phase = "执行失败"; add(sequence, "activity", payload.text("message")) }
            "run.completed" -> { preview = ""; runId = ""; phase = "已完成" }
            "run.cancelled" -> { preview = ""; runId = ""; phase = if (payload.text("reason") == "paused") "已暂停" else "已停止" }
        }
        if (!ephemeral) cursor = sequence
    }

    private fun add(sequence: Long, role: String, text: String) {
        if (text.isBlank()) return
        rows.add(TranscriptRow(sequence.toString(), role, if (text.length > 128_000) text.take(128_000) + "\n\n（内容较长，当前显示前 128,000 字符；完整内容请在电脑端查看。）" else text))
        rowChars += rows.last().text.length
        while (rows.size > maxRows || rowChars > 4_000_000) rowChars -= rows.removeAt(0).text.length
    }
}

/** 按字节积累到换行再解码，覆盖 UTF-8 字符跨网络分片和 CRLF。 */
class SseDecoder(private val emit: (JsonObject) -> Unit) {
    private val line = ByteArrayOutputStream()
    private val data = StringBuilder()
    fun feed(bytes: ByteArray) {
        for (byte in bytes) {
            if (byte == 10.toByte()) {
                val value = line.toString("UTF-8").removeSuffix("\r"); line.reset()
                if (value.isEmpty()) {
                    if (data.isNotEmpty()) {
                        emit(Protocol.json.parseToJsonElement(data.toString().trimEnd('\n')).jsonObject)
                        data.clear()
                    }
                } else if (value.startsWith("data:")) {
                    data.append(value.removePrefix("data:").removePrefix(" ")).append('\n')
                    require(data.length <= Protocol.MAX_FRAME) { "事件过大" }
                }
            } else {
                require(line.size() < Protocol.MAX_FRAME) { "事件行过大" }; line.write(byte.toInt())
            }
        }
    }
}
