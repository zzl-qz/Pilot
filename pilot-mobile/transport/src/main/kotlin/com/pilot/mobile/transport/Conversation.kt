package com.pilot.mobile.transport

import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream

fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: ""
fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
fun JsonObject.long(key: String): Long? = text(key).toLongOrNull()
fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

data class TranscriptRow(val key: String, val role: String, val text: String)

/** 持久事件去重；预览与最终正文分开，续接不会把一次回复重复拼接。 */
class Conversation(private val maxRows: Int = 300) {
    var cursor: Long = 0; private set
    var historyRevision: Long? = null; private set
    var needsReplay = false; private set
    private val rows = mutableListOf<TranscriptRow>()
    private var preview = ""
    private var thinking = ""
    private var previewId = ""
    private var previewOffset = 0L
    private var rowChars = 0
    var runId = ""; private set
    var phase = ""; private set
    fun snapshot(): List<TranscriptRow> {
        val live = buildList {
            if (thinking.isNotBlank()) add(TranscriptRow("thinking", "thinking", thinking))
            if (preview.isNotEmpty()) add(TranscriptRow("preview", "assistant", preview))
        }
        return rows + live
    }
    fun reconnect() { preview = ""; thinking = ""; needsReplay = false }

    fun historyVersion(revision: Long) { historyRevision = revision }

    fun accept(event: JsonObject) {
        val type = event.text("type")
        val payload = event["payload"] as? JsonObject ?: return
        if (type == "stream.ready" || type == "stream.reset") {
            val revision = payload.long("historyRevision") ?: error("历史版本无效")
            if (type == "stream.reset" || historyRevision != null && historyRevision != revision) {
                rows.clear(); rowChars = 0; preview = ""; thinking = ""; cursor = 0; runId = ""; phase = "重新加载历史"
                needsReplay = true
            }
            historyRevision = revision
            return
        }
        if (needsReplay) return
        if (type == "stream.preview") {
            require(payload.long("historyRevision") == historyRevision && payload.long("baseSequence") == cursor) { "临时输出历史边界不匹配" }
            require(payload.text("runId").isBlank() || runId.isBlank() || payload.text("runId") == runId) { "临时输出运行不匹配" }
            preview = payload.text("text").take(128_000)
            val reasoning = payload.text("reasoning")
            if (reasoning.isNotBlank()) thinking = reasoning.take(4_000)
            previewId = payload.text("previewId"); previewOffset = payload.long("previewOffset") ?: error("临时输出偏移无效")
            if (!payload.bool("complete")) phase = "临时输出未完整保留，等待正式结果"
            return
        }
        if (type.startsWith("stream.")) return
        if (type == "assistant.reasoning") {
            val reset = payload.bool("reset")
            thinking = ((if (reset) "" else thinking) + payload.text("text")).takeLast(4_000)
            if (thinking.isNotBlank()) phase = "正在思考"
            return
        }
        val ephemeral = type == "assistant.preview"
        val sequence = event.long("sequence") ?: return
        if (!ephemeral && sequence <= cursor) return
        if (ephemeral && event.text("previewId").isNotBlank()) {
            val offset = event.long("previewOffset") ?: error("临时增量偏移无效")
            if (previewId == event.text("previewId") && offset <= previewOffset) return
            previewId = event.text("previewId"); previewOffset = offset
        }
        val id = event.text("runId")
        when (type) {
            "run.started" -> {
                runId = id; phase = "正在执行"; preview = ""; thinking = ""
                val input = payload.text("input")
                if (rows.lastOrNull()?.let { it.role == "user" && it.text == input } != true) add(sequence, "user", input)
            }
            "user.message" -> add(sequence, "user", payload.text("input").ifBlank { payload.text("text") })
            "assistant.preview" -> {
                // reset：请求重试或输出被判为复读，上一次尝试的片段作废。
                if (payload.bool("reset")) preview = ""
                else if (id == runId || runId.isBlank()) preview = (preview + payload.text("text")).takeLast(128_000)
            }
            "assistant.delta" -> { preview = ""; thinking = ""; add(sequence, "assistant", payload.text("text")) }
            "tool.started" -> { phase = "正在调用 ${payload.text("name")}"; add(sequence, "activity", phase + "\n" + payload.text("summary")) }
            "tool.completed" -> {
                val summary = payload.text("resultSummary").take(160)
                phase = "工具已返回"
                add(sequence, "activity", if (summary.isBlank()) "工具已返回" else "完成 · $summary")
            }
            "approval.required" -> phase = "等待你的确认"
            "run.steered" -> add(sequence, "user", payload.text("content").ifBlank { payload.text("input") })
            "run.continued" -> { runId = id; phase = "正在继续" }
            "run.failed" -> { preview = ""; thinking = ""; runId = ""; phase = "执行失败"; add(sequence, "activity", payload.text("message")) }
            "run.completed" -> { preview = ""; thinking = ""; runId = ""; phase = "已完成" }
            "run.cancelled" -> { preview = ""; thinking = ""; runId = ""; phase = if (payload.text("reason") == "paused") "已暂停" else "已停止" }
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
