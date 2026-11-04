package com.pilot.mobile

import com.pilot.mobile.transport.Request
import com.pilot.mobile.transport.TranscriptRow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class MobileAttachment(val name: String, val mimeType: String, val dataBase64: String, val bytes: Int)
@Serializable
data class TaskDraft(val text: String = "", val attachments: List<MobileAttachment> = emptyList())

data class MobileState(
    val computer: String = "", val computerId: String = "", val computers: List<PairedComputer> = emptyList(),
    val status: String = "未连接", val networkError: String = "", val error: String = "", val busy: Boolean = false, val tab: String = "任务",
    val sessions: List<JsonObject> = emptyList(), val projects: List<JsonObject> = emptyList(),
    val results: List<JsonObject> = emptyList(), val capabilities: JsonObject? = null,
    val selected: JsonObject? = null, val rows: List<TranscriptRow> = emptyList(),
    val runId: String = "", val phase: String = "", val approvals: List<JsonObject> = emptyList(),
    val queued: List<String> = emptyList(), val queueRevision: String = "", val draft: String = "",
    val attachments: List<MobileAttachment> = emptyList(), val pins: Set<String> = emptySet(),
    val viewingHistory: Boolean = false, val limitedHistory: Boolean = false, val pending: Request? = null, val blankProject: JsonObject? = null,
    val detailTitle: String = "", val detail: JsonObject? = null, val browse: JsonObject? = null,
)
