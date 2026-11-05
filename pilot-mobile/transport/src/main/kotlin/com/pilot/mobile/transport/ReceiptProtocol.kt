package com.pilot.mobile.transport

import kotlinx.serialization.json.*

/** 有效期完全由电脑端签发；App 不根据手机时钟改写标识。 */
object ReceiptProtocol {
    const val TOKEN_PATH = "/api/mobile/receipts/token"
    const val ACK_PATH = "/api/mobile/receipts/ack"
    private val current = Regex("r3\\.[0-9]{1,16}\\.[a-f0-9-]{36}\\.[a-f0-9]{64}")
    fun validId(id: String): Boolean = current.matches(id) || Regex("[a-f0-9-]{36}").matches(id)
    fun issuedId(response: Pair<ResponseHead, String>): String {
        val body = runCatching { Protocol.json.parseToJsonElement(response.second).jsonObject }.getOrNull()
        require(response.first.status in 200..299) {
            body?.get("message")?.jsonPrimitive?.content ?: "电脑不支持安全回执，请更新电脑后台和连接组件"
        }
        val id = body?.get("requestId")?.jsonPrimitive?.content.orEmpty()
        require(body?.get("receiptVersion")?.jsonPrimitive?.intOrNull == 3 && current.matches(id)) {
            "电脑回执协议不兼容，请更新电脑后台和连接组件"
        }
        return id
    }
    fun confirmable(status: Int, parsed: JsonElement?): Boolean =
        status in 200..499 && status !in setOf(401, 403, 408, 410, 426, 429) && parsed is JsonObject &&
            parsed["uncertain"]?.jsonPrimitive?.booleanOrNull != true

    fun acknowledge(id: String): Request = Request("POST", ACK_PATH, buildJsonObject { put("requestId", id) }.toString())
}
