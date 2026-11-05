package com.pilot.mobile.bridge

import com.pilot.mobile.transport.*
import computer.iroh.SecretKey
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** 对隔离的真实 Pilot Jar 验收接口契约；只建空会话，不调用模型。 */
fun main() = runBlocking {
    val invite = PairingInvite.decode(System.getenv("PILOT_TEST_INVITE"))
    val key = System.getenv("PILOT_TEST_PHONE_KEY")?.let { java.util.Base64.getDecoder().decode(it) } ?: SecretKey.generate().toBytes()
    val endpoint = createEndpoint(key, invite.relays, invite.developmentRelays)
    val client = RemoteClient(endpoint, invite.ticket, relays = invite.relays, allowPublicRelays = invite.developmentRelays)
    suspend fun prepare(request: Request): Request = if (request.method != "GET" && request.path !in listOf("/pair", ReceiptProtocol.ACK_PATH) && request.requestId == null)
        request.copy(requestId = ReceiptProtocol.issuedId(client.request(Request(path = ReceiptProtocol.TOKEN_PATH)))) else request
    suspend fun json(request: Request): JsonElement {
        val (head, body) = client.request(prepare(request))
        check(head.status in 200..299) { "真实服务返回 ${head.status}" }
        return Protocol.json.parseToJsonElement(body)
    }
    try {
        if (System.getenv("PILOT_TEST_RECONNECT") == "true") {
            // App 前台监控会退避重试；桥进程存在不代表公网地址已经发布完成。
            val started = System.currentTimeMillis()
            var sessions: JsonArray? = null
            var retries = 0
            while (sessions == null) {
                try { sessions = json(Request()).jsonArray }
                catch (e: java.io.IOException) {
                    if (++retries >= 3) throw e
                    delay(1000)
                }
            }
            println("恢复读取耗时 ${System.currentTimeMillis() - started} ms，连接重试 $retries 次")
            check(sessions.size == 1 && sessions.single().jsonObject.text("title") == "手机重命名任务")
            check(json(Request(path = "/api/mobile/workbench")).jsonObject.text("version") == "1")
            val project = json(Request(path = "/api/projects")).jsonArray.single().jsonObject.text("id")
            val repeated = Request("POST", "/api/sessions", buildJsonObject { put("projectId", project); put("title", "移动契约验收"); put("replaceBlank", false) }.toString(), requestId = java.io.File(System.getenv("PILOT_TEST_REQUEST_FILE")).readText())
            check(json(repeated).jsonObject.text("id") == sessions.single().jsonObject.text("id"))
            println("PASS: 原手机身份和旧连接票据恢复访问，任务与提交回执保留，无需重新配对")
            return@runBlocking
        }
        json(Request("POST", "/pair", pairingSecret = invite.secret, deviceName = "契约测试"))
        val projects = json(Request(path = "/api/projects")).jsonArray
        val id = projects.single().jsonObject.text("id")
        val creation = prepare(Request("POST", "/api/sessions", buildJsonObject { put("projectId", id); put("title", "移动契约验收"); put("replaceBlank", false) }.toString(), requestId = null))
        java.io.File(System.getenv("PILOT_TEST_REQUEST_FILE")).writeText(creation.requestId!!)
        val session = json(creation).jsonObject.text("id")
        check(json(creation).jsonObject.text("id") == session) { "重复提交必须返回相同任务" }
        json(ReceiptProtocol.acknowledge(creation.requestId!!))
        val snapshot = json(Request(path = "/api/mobile/workbench")).jsonObject
        check(snapshot.text("version") == "1")
        val snapshotRevision = snapshot.text("revision")
        check(snapshotRevision.matches(Regex("[a-f0-9]{64}")))
        val unchanged = json(Request(path = "/api/mobile/workbench", snapshotRevision = snapshotRevision)).jsonObject
        check(unchanged["unchanged"]!!.jsonPrimitive.boolean && unchanged.text("revision") == snapshotRevision)
        check("sessions" !in unchanged && unchanged.toString().toByteArray().size < 160)
        check(json(Request("PATCH", "/api/sessions/$session", "{\"title\":\"手机重命名任务\"}")).jsonObject.text("title") == "手机重命名任务")
        val changed = json(Request(path = "/api/mobile/workbench", snapshotRevision = snapshotRevision)).jsonObject
        check(changed.text("revision") != snapshotRevision && changed["sessions"]!!.jsonArray.single().jsonObject.text("title") == "手机重命名任务")
        println("条件快照：全量 ${snapshot.toString().toByteArray().size} bytes，未变化 ${unchanged.toString().toByteArray().size} bytes，修改后返回新状态")
        check(json(Request("PATCH", "/api/sessions/$session/archive", "{\"archived\":true}")).jsonObject["archived"]!!.jsonPrimitive.boolean)
        check(!json(Request("PATCH", "/api/sessions/$session/archive", "{\"archived\":false}")).jsonObject["archived"]!!.jsonPrimitive.boolean)
        check(client.request(Request(path = "/api/mobile/connection")).first.status == 403)
        check(session.isNotBlank())
        check(json(Request()).jsonArray.any { it.jsonObject.text("id") == session })
        check(json(Request(path = "/api/sessions/$session/history?limit=300")).jsonObject["events"]!!.jsonArray.isEmpty())
        check(json(Request(path = "/api/sessions/$session/runs/active")).jsonObject.text("runId").isBlank())
        check(json(Request(path = "/api/sessions/$session/queue")).jsonObject["items"]!!.jsonArray.isEmpty())
        check(json(Request(path = "/api/approvals")).jsonArray.isEmpty())
        var received = false
        withTimeoutOrNull(1500) { client.transfer(Request(path = "/api/sessions/$session/events?after=0")) { received = true } }
        check(received) { "SSE 连接没有收到服务端首帧" }
        check(client.request(prepare(Request("POST", "/api/runs/not_found/pause", "{}"))).first.status == 409)
        check(client.request(Request(path = "/api/settings")).first.status == 403)
        println("PASS: 真实 Pilot Jar 令牌鉴权、项目/会话/历史/队列/审批接口、SSE 首帧、错误状态；模型调用为 0")
    } finally { client.disconnect(); endpoint.shutdown() }
}
