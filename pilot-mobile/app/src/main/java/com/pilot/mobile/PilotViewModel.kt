package com.pilot.mobile

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pilot.mobile.transport.*
import computer.iroh.Endpoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID

class PilotViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SecureDeviceStore(application)
    private val mutable = MutableStateFlow(MobileState(busy = true))
    val state = mutable.asStateFlow()
    private var endpoint: Endpoint? = null
    private var client: RemoteClient? = null
    private var monitor: Job? = null
    private var draftSave: Job? = null
    private var conversation = Conversation(6000)
    private val historyEvents = sortedMapOf<Long, JsonObject>()
    private var foreground = false
    private var historyChars = 0

    init {
        viewModelScope.launch {
            try {
                (application as PilotApplication).transportFailure?.let { error(it) }
                mutable.update { it.copy(computers = store.computers()) }
                store.computer()?.let { connect(it) }
            } catch (e: Exception) { fail(e) } finally { mutable.update { it.copy(busy = false) } }
        }
    }

    private suspend fun connect(computer: PairedComputer) {
        saveDraft(); monitor?.cancelAndJoin(); client?.disconnect(); endpoint?.shutdown()
        store.select(computer.id)
        mutable.value = MobileState(computer = computer.name, computerId = computer.id, computers = store.computers(), status = "正在连接", busy = state.value.busy,
            pins = store.value(computer.id, "pins")?.split('\n')?.filter { it.isNotBlank() }?.toSet().orEmpty(),
            pending = store.value(computer.id, "pending")?.let { Protocol.json.decodeFromString(Request.serializer(), it) })
        endpoint = createEndpoint(store.identity(), computer.relays, computer.development)
        client = RemoteClient(endpoint!!, computer.ticket)
        restartMonitor()
    }

    fun switchComputer(computer: PairedComputer) = action { connect(computer) }

    fun pair(code: String) = action {
        val invite = PairingInvite.decode(code)
        val computer = PairedComputer(invite.ticket, invite.name, invite.relays, invite.developmentRelays)
        require(store.computers().size < 16 || store.computers().any { it.id == computer.id }) { "最多保存 16 台电脑" }
        val candidateEndpoint = createEndpoint(store.identity(), computer.relays, computer.development)
        val candidate = RemoteClient(candidateEndpoint, computer.ticket)
        try {
            val response = candidate.request(Request("POST", "/pair", pairingSecret = invite.secret, deviceName = android.os.Build.MODEL))
            require(response.first.status == 200) { "配对码失效或已使用，请在电脑上重新生成" }
            store.save(computer)
        } finally { candidate.disconnect(); candidateEndpoint.shutdown() }
        connect(computer)
    }

    fun setForeground(active: Boolean) {
        foreground = active
        if (active) restartMonitor() else { monitor?.cancel(); monitor = null; persistDraftSoon(0) }
    }

    private fun restartMonitor() {
        monitor?.cancel()
        if (!foreground || client == null) return
        monitor = viewModelScope.launch {
            var backoff = 1000L
            while (isActive) {
                try {
                    val id = state.value.selected?.text("id")
                    if (id == null || state.value.viewingHistory) { refresh(); delay(5000) }
                    else coroutineScope {
                        conversation.reconnect()
                        val metadata = launch { while (isActive) { refresh(); refreshRun(id); delay(5000) } }
                        try {
                            val decoder = SseDecoder { event ->
                                if (event.text("type") !in setOf("assistant.preview", "assistant.reasoning")) recordEvent(event, false)
                                conversation.accept(event)
                                mutable.update { it.copy(rows = conversation.snapshot(), runId = if (event.text("type") in setOf("run.started", "run.continued", "run.completed", "run.failed", "run.cancelled")) conversation.runId else it.runId, phase = conversation.phase.ifBlank { it.phase }, status = client?.pathLabel() ?: "已断开") }
                            }
                            val response = client!!.transfer(Request(path = "/api/sessions/$id/events?after=${conversation.cursor}")) { decoder.feed(it) }
                            if (response.status == 401) error("设备授权已失效，请重新配对")
                            require(response.status in 200..299) { "无法读取任务进度（${response.status}）" }
                        } finally { metadata.cancelAndJoin() }
                    }
                    backoff = 1000
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    mutable.update { it.copy(status = "连接中断 · 正在重试", networkError = e.message ?: "连接失败") }
                    delay(backoff); backoff = (backoff * 2).coerceAtMost(15_000)
                }
            }
        }
    }

    private suspend fun refresh() {
        val snapshot = api(Request(path = "/api/mobile/workbench")).jsonObject
        require(snapshot.text("version") == "1" && (snapshot["capabilities"] as? JsonObject)?.bool("idempotency") == true) { "电脑端协议版本不兼容，请更新 Pilot" }
        val sessions = snapshot.objects("sessions")
        mutable.update { it.copy(sessions = sessions, projects = snapshot.objects("projects"), approvals = snapshot.objects("approvals"),
            results = (snapshot["results"] as? JsonObject)?.objects("items").orEmpty(), capabilities = snapshot["capabilities"] as? JsonObject,
            selected = it.selected?.let { old -> sessions.firstOrNull { s -> s.text("id") == old.text("id") } ?: old },
            status = client?.pathLabel() ?: "未连接", networkError = "") }
    }

    fun select(session: JsonObject) = action { openSession(session) }
    fun openTask(id: String) = action { openSession(api(Request(path = "/api/sessions/$id")).jsonObject) }
    private suspend fun openSession(session: JsonObject) {
        saveDraft(); monitor?.cancelAndJoin()
        conversation = Conversation(6000); historyEvents.clear(); historyChars = 0
        val draft = loadDraft(session.text("id"))
        mutable.update { it.copy(selected = session, rows = emptyList(), runId = "", phase = "读取历史…", queued = emptyList(), viewingHistory = false, limitedHistory = false,
            draft = draft.text, attachments = draft.attachments, detail = null) }
        try { loadHistory(false); refreshRun(session.text("id")) } finally { restartMonitor() }
    }

    fun older() = action { mutable.update { it.copy(viewingHistory = true) }; monitor?.cancelAndJoin(); try { loadHistory(true) } finally { restartMonitor() } }
    fun latest() = action { state.value.selected?.let { openSession(it) } }
    private suspend fun loadHistory(older: Boolean) {
        val id = state.value.selected!!.text("id")
        val before = if (older && historyEvents.isNotEmpty()) "&before=${historyEvents.firstKey()}" else ""
        val page = api(Request(path = "/api/sessions/$id/history?limit=300$before")).jsonObject
        val events = page.objects("events")
        events.forEach { recordEvent(it, older) }
        conversation = Conversation(6000)
        historyEvents.values.forEach(conversation::accept)
        mutable.update { it.copy(rows = conversation.snapshot(), phase = conversation.phase, limitedHistory = page.bool("hasMore")) }
    }

    private fun recordEvent(event: JsonObject, keepOlder: Boolean) {
        val sequence = event.text("sequence").toLongOrNull() ?: return
        val old = historyEvents.put(sequence, event)
        historyChars += event.toString().length - (old?.toString()?.length ?: 0)
        while (historyEvents.size > 1 && (historyEvents.size > 5000 || historyChars > 4_000_000)) {
            val removed = historyEvents.remove(if (keepOlder) historyEvents.lastKey() else historyEvents.firstKey())
            historyChars -= removed?.toString()?.length ?: 0
            mutable.update { it.copy(limitedHistory = true) }
        }
    }

    private suspend fun refreshRun(id: String) {
        val active = api(Request(path = "/api/sessions/$id/runs/active")).jsonObject.text("runId")
        val queue = api(Request(path = "/api/sessions/$id/queue")).jsonObject
        mutable.update { if (it.selected?.text("id") != id) it else it.copy(runId = active,
            phase = if (active.isBlank()) statusLabel(it.selected.text("status")) else it.phase.ifBlank { "正在执行" },
            queued = queue["items"]?.jsonArray?.map { value -> value.jsonPrimitive.content }.orEmpty(), queueRevision = queue.text("revision")) }
    }

    fun back() = action { saveDraft(); mutable.update { it.copy(selected = null, draft = "", attachments = emptyList(), detail = null) }; restartMonitor() }
    fun tab(tab: String) { if (!state.value.busy) mutable.update { it.copy(tab = tab) } }
    fun draft(text: String) { mutable.update { it.copy(draft = text.take(32_000)) }; persistDraftSoon() }
    fun clearError() { mutable.update { it.copy(error = "", networkError = "") } }
    fun report(message: String) { mutable.update { it.copy(error = message) } }
    private fun loadDraft(id: String): TaskDraft = store.value(state.value.computerId, "draft:$id")?.let { Protocol.json.decodeFromString(TaskDraft.serializer(), it) } ?: TaskDraft()
    private fun persistDraftSoon(delayMs: Long = 400) {
        val current = state.value
        val id = current.selected?.text("id") ?: return
        draftSave?.cancel()
        draftSave = viewModelScope.launch {
            delay(delayMs)
            persistDraft(current.computerId, id, TaskDraft(current.draft, current.attachments))
        }
    }
    private suspend fun persistDraft(host: String, id: String, draft: TaskDraft) = withContext(Dispatchers.IO) {
        store.value(host, "draft:$id", if (draft.text.isEmpty() && draft.attachments.isEmpty()) null else Protocol.json.encodeToString(TaskDraft.serializer(), draft))
    }
    private suspend fun saveDraft() {
        draftSave?.cancelAndJoin()
        val current = state.value
        current.selected?.text("id")?.let { persistDraft(current.computerId, it, TaskDraft(current.draft, current.attachments)) }
    }

    fun attach(uris: List<Uri>) = action {
        val selected = state.value.selected?.text("id") ?: return@action
        val existing = state.value.attachments
        // 先拒绝过多文件，再按剩余额度读取；不能等整批 Base64 都进内存才校验。
        require(uris.size <= 16 - existing.size) { "一次最多 16 个附件" }
        val attachments = withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            var totalBytes = existing.sumOf { it.bytes }
            uris.map { uri ->
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }?.take(200) ?: "附件"
                val remaining = (6 * 1024 * 1024 - totalBytes).coerceAtLeast(0)
                val bytes = resolver.openInputStream(uri)?.use { it.readBounded(minOf(2 * 1024 * 1024, remaining) + 1) } ?: error("无法读取 $name")
                require(bytes.isNotEmpty() && bytes.size <= 2 * 1024 * 1024) { "$name 需大于 0 且不超过 2 MB" }
                totalBytes += bytes.size
                require(totalBytes <= 6 * 1024 * 1024) { "一次附件总量最多 6 MB" }
                MobileAttachment(name, resolver.getType(uri) ?: "application/octet-stream", Base64.getEncoder().encodeToString(bytes), bytes.size)
            }
        }
        if (state.value.selected?.text("id") == selected) mutable.update { it.copy(attachments = it.attachments + attachments) }
        saveDraft()
    }
    fun removeAttachment(index: Int) { mutable.update { it.copy(attachments = it.attachments.filterIndexed { i, _ -> i != index }) }; persistDraftSoon() }

    fun pin(id: String) = action {
        val next = if (id in state.value.pins) state.value.pins - id else state.value.pins + id
        store.value(state.value.computerId, "pins", next.joinToString("\n")); mutable.update { it.copy(pins = next) }
    }
    fun rename(session: JsonObject, title: String) = action { require(title.isNotBlank()); api(Request("PATCH", "/api/sessions/${session.text("id")}", json("title" to title.trim()).toString())); refresh() }
    fun archive(session: JsonObject) = action {
        api(Request("PATCH", "/api/sessions/${session.text("id")}/archive", buildJsonObject { put("archived", !session.bool("archived")) }.toString())); refresh()
    }
    fun create(project: JsonObject, replace: Boolean = false) = action {
        if (!replace && project.text("blankSessionId").isNotBlank()) { mutable.update { it.copy(blankProject = project) }; return@action }
        val body = buildJsonObject { put("projectId", project.text("id")); put("title", "新任务"); put("replaceBlank", replace) }
        val session = api(Request("POST", "/api/sessions", body.toString())).jsonObject
        mutable.update { it.copy(blankProject = null) }; refresh(); openSession(session)
    }
    fun dismissBlank() { mutable.update { it.copy(blankProject = null) } }
    fun createProject(name: String, folder: String) = action {
        require(name.isNotBlank() && folder.isNotBlank()) { "请输入项目名称并选择电脑上的目录" }
        api(Request("POST", "/api/projects", buildJsonObject { put("name", name.trim()); putJsonArray("folders") { add(folder) } }.toString())); refresh()
    }
    fun renameProject(project: JsonObject, name: String) = action { require(name.isNotBlank()); api(Request("PATCH", "/api/projects/${project.text("id")}", json("name" to name.trim()).toString())); refresh() }
    fun browse(path: String? = null) = action { mutable.update { it.copy(browse = api(Request(path = "/api/workspaces/browse" + (path?.let { "?path=${encode(it)}" } ?: ""))).jsonObject) } }
    fun clearBrowse() { mutable.update { it.copy(browse = null) } }

    fun send() = action {
        val current = state.value
        val id = current.selected?.text("id") ?: return@action
        val text = current.draft.trim()
        require(text.isNotEmpty() || current.attachments.isNotEmpty()) { "请输入任务内容" }
        val body = buildJsonObject { put("content", text); put("attachments", buildJsonArray { current.attachments.forEach { attachment -> add(buildJsonObject {
            put("name", attachment.name); put("mimeType", attachment.mimeType); put("dataBase64", attachment.dataBase64)
        }) } }) }
        api(Request("POST", "/api/sessions/$id/messages", body.toString()))
        mutable.update { it.copy(draft = if (it.draft == current.draft) "" else it.draft, attachments = it.attachments.filterNot { a -> a in current.attachments }) }
        saveDraft(); refreshRun(id)
    }
    fun control(operation: String) = action {
        val current = state.value
        require(operation in setOf("pause", "cancel", "continue"))
        val path = if (operation == "continue") "/api/sessions/${current.selected!!.text("id")}/continue" else "/api/runs/${current.runId}/$operation"
        api(Request("POST", path, "{}")); refresh(); refreshRun(current.selected!!.text("id"))
    }
    fun queue(index: Int, operation: String, content: String = "", expectedRevision: String? = null) = action {
        val current = state.value
        require(expectedRevision == null || current.queueRevision == expectedRevision) { "队列已变化，请重新查看后操作" }
        val path = "/api/sessions/${current.selected!!.text("id")}/queue/$index"
        val request = when (operation) {
            "steer" -> Request("POST", "$path/steer", json("runId" to current.runId, "revision" to current.queueRevision).toString())
            "edit" -> Request("PUT", path, json("content" to content, "revision" to current.queueRevision).toString())
            "remove" -> Request("DELETE", "$path?revision=${encode(current.queueRevision)}")
            "up" -> Request("POST", "$path/move?to=${index - 1}&revision=${encode(current.queueRevision)}", "{}")
            else -> error("不支持的队列操作")
        }
        try { api(request) } finally { refreshRun(current.selected.text("id")) }
    }
    fun steer(index: Int) = queue(index, "steer")
    fun approve(id: String, approved: Boolean) = action {
        require(state.value.approvals.any { it.text("callId") == id && it.text("sessionId").isNotBlank() }) { "任务归属尚未确认，请刷新后重试" }
        try { api(Request("POST", "/api/approvals/$id", "{\"approved\":$approved}")) } finally { refresh() }
    }
    fun previewProduct(product: JsonObject) = detail(product.text("title"), "/api/sessions/${product.text("sessionId")}/products/${product.text("id")}/preview")
    fun download(product: JsonObject, destination: Uri) = action {
        val output = java.io.ByteArrayOutputStream()
        val response = client?.transfer(Request(path = "/api/sessions/${product.text("sessionId")}/products/${product.text("id")}/download")) { chunk ->
            require(output.size() + chunk.size <= Protocol.MAX_RESPONSE) { "文件超过 16 MB，请在电脑端下载" }; output.write(chunk)
        } ?: error("请先连接电脑")
        require(response.status == 200) { "下载失败（${response.status}），请刷新成果后重试" }
        withContext(Dispatchers.IO) {
            val bytes = output.toByteArray()
            val expected = product.text("sha256")
            if (expected.isNotBlank()) {
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                require(digest == expected) { "文件校验失败，请重新下载" }
            }
            getApplication<Application>().contentResolver.openOutputStream(destination, "wt")?.use { it.write(bytes) } ?: error("无法保存到所选位置")
        }
        mutable.update { it.copy(detail = null) }
    }
    fun result(item: JsonObject) = detail("任务结果", "/api/results/${item.text("id")}")
    fun receipt(item: JsonObject, action: String) = action { api(Request("POST", "/api/results/${item.text("id")}/receipt", json("revision" to item.text("revision"), "action" to action).toString())); refresh() }
    fun inspect(kind: String) { val id = state.value.selected?.text("id") ?: return; detail(when(kind) { "diff" -> "工作区改动"; "context" -> "上下文与用量"; else -> "任务成果" }, "/api/sessions/$id/$kind") }
    fun fileDiff(path: String) { val id = state.value.selected?.text("id") ?: return; detail(path, "/api/sessions/$id/diff/file?path=${encode(path)}") }
    private fun detail(title: String, path: String) = action {
        val value = api(Request(path = path))
        var objectValue = value as? JsonObject ?: buildJsonObject { put("items", value) }
        if (path.endsWith("/context")) {
            val usage = historyEvents.values.lastOrNull { it.text("type") == "run.usage" }?.get("payload") as? JsonObject
            objectValue = buildJsonObject {
                objectValue.forEach { (key, entry) -> put(key, entry) }
                if (usage != null) {
                    val details = usage["details"] as? JsonObject
                    val observed = details?.text("observedCacheInputTokens")?.toDoubleOrNull() ?: 0.0
                    val cached = details?.text("cacheReadTokens")?.toDoubleOrNull() ?: 0.0
                    put("usage", buildJsonObject {
                        put("输入 Tokens", usage.text("inputTokens")); put("输出 Tokens", usage.text("outputTokens"))
                        put("请求数", usage.text("modelRequests")); put("缓存命中率", if (observed > 0) "${"%.1f".format(cached / observed * 100)}%" else "未知（接口未提供）")
                        val costs = details?.get("costs") as? JsonObject
                        put("费用估算", if (costs.isNullOrEmpty()) "未知（未配置价格）" else costs.entries.joinToString { "${it.value.jsonPrimitive.content} ${it.key}" })
                        put("计价请求", "${details?.text("pricedRequests") ?: "0"} / ${usage.text("modelRequests")}")
                    })
                }
            }
        }
        mutable.update { it.copy(detailTitle = title, detail = objectValue) }
    }
    fun closeDetail() { mutable.update { it.copy(detail = null) } }

    fun retryPending() = action {
        val pending = state.value.pending ?: return@action
        saveDraft()
        val response = api(pending, replay = true)
        if (pending.path.endsWith("/messages")) {
            val body = Protocol.json.parseToJsonElement(pending.body).jsonObject
            val id = pending.path.split('/')[3]
            val draft = loadDraft(id)
            val sent = body.text("content")
            val sentAttachments = body.objects("attachments")
            val next = draft.copy(text = if (draft.text.trim() == sent) "" else draft.text, attachments = draft.attachments.filterNot { attachment -> sentAttachments.any { it.text("name") == attachment.name && it.text("dataBase64") == attachment.dataBase64 } })
            persistDraft(state.value.computerId, id, next)
            if (state.value.selected?.text("id") == id) mutable.update { it.copy(draft = next.text, attachments = next.attachments) }
        }
        refresh(); state.value.selected?.let { openSession(it) }
        if (pending.path == "/api/sessions" && response is JsonObject) openSession(response)
    }
    fun dismissPending() = action { store.value(state.value.computerId, "pending", null); mutable.update { it.copy(pending = null) } }
    fun retry() { clearError(); restartMonitor() }
    fun forget(id: String) = action {
        if (id == state.value.computerId) { saveDraft(); monitor?.cancelAndJoin(); client?.disconnect(); client = null; endpoint?.shutdown(); endpoint = null }
        store.forget(id)
        if (id == state.value.computerId) { mutable.value = MobileState(computers = store.computers()); store.computer()?.let { connect(it) } }
        else mutable.update { it.copy(computers = store.computers()) }
    }

    private suspend fun api(original: Request, replay: Boolean = false): JsonElement {
        val mutation = original.method != "GET"
        val request = if (mutation) original.copy(requestId = original.requestId ?: UUID.randomUUID().toString()) else original
        if (mutation) {
            require(replay || state.value.pending == null) { "有一条指令结果尚未确认，请先核对提交结果" }
            withContext(Dispatchers.IO) { store.value(state.value.computerId, "pending", Protocol.json.encodeToString(Request.serializer(), request)) }
            mutable.update { it.copy(pending = request) }
        }
        val result = client?.request(request) ?: error("请先连接电脑")
        val parsed = runCatching { Protocol.json.parseToJsonElement(result.second) }.getOrNull()
        val uncertain = result.first.status >= 500 || (parsed as? JsonObject)?.bool("uncertain") == true
        if (mutation && !uncertain) {
            withContext(Dispatchers.IO) { store.value(state.value.computerId, "pending", null) }
            mutable.update { it.copy(pending = null) }
        }
        if (result.first.status !in 200..299) {
            val message = (parsed as? JsonObject)?.let { it.text("error").ifBlank { it.text("message") } }.orEmpty()
            error(message.ifBlank { "请求失败（${result.first.status}）" })
        }
        return parsed ?: JsonNull
    }
    private fun action(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, error = "") }
        viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    private fun fail(error: Exception) { mutable.update { it.copy(error = error.message ?: "操作失败，请重试") } }
    override fun onCleared() { monitor?.cancel(); client?.disconnect(); endpoint?.let { ep -> CoroutineScope(Dispatchers.IO).launch { ep.shutdown() } } }
}

fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
fun json(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { (key, value) -> put(key, value) } }
fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
fun statusLabel(status: String): String = when (status) {
    "RUNNING" -> "执行中"; "WAITING_APPROVAL" -> "待确认"; "COMPLETED" -> "已完成"; "FAILED" -> "失败"
    "PAUSED" -> "已暂停"; "CANCELLED" -> "已停止"; "INTERRUPTED" -> "已中断"; else -> "待开始"
}

private fun java.io.InputStream.readBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
