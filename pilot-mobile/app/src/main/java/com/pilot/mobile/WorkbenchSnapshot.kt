package com.pilot.mobile

import com.pilot.mobile.transport.Request
import com.pilot.mobile.transport.text
import java.io.IOException
import kotlinx.serialization.json.JsonObject

/** 当前电脑的内存快照；旧连接桥忽略新增请求字段，自动退回全量读取。 */
internal class WorkbenchSnapshot {
    private var revision: String? = null
    private var saved: JsonObject? = null

    fun request() = Request(path = "/api/mobile/workbench", snapshotRevision = revision)

    fun accept(value: JsonObject): JsonObject {
        require(value.text("version") == "1") { "电脑端协议版本不兼容，请更新 Pilot" }
        if (value.bool("unchanged")) {
            if (saved == null || revision == null || value.text("revision") != revision) {
                revision = null
                throw IOException("工作台状态需要重新同步，请刷新重试")
            }
            return saved!!
        }
        require((value["capabilities"] as? JsonObject)?.bool("idempotency") == true) { "电脑端协议版本不兼容，请更新 Pilot" }
        saved = value
        revision = if ((value["capabilities"] as? JsonObject)?.bool("conditionalSnapshot") == true)
            value.text("revision").takeIf { it.matches(Regex("[a-f0-9]{64}")) } else null
        return value
    }
}
