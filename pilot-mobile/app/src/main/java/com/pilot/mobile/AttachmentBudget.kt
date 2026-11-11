package com.pilot.mobile

import com.pilot.mobile.transport.text
import kotlinx.serialization.json.JsonObject

/** 服务端配置与手机传输上限的交集；旧电脑端未声明时保留原有手机上限。 */
internal data class AttachmentBudget(val maxFiles: Int, val maxFileBytes: Int, val maxTotalBytes: Int, val maxRequestBytes: Int) {
    fun validate(files: List<MobileAttachment>) {
        require(files.size <= maxFiles) { "一次最多 $maxFiles 个附件" }
        require(files.all { it.bytes in 1..maxFileBytes }) { "单个附件需大于 0 且不超过 $maxFileBytes 字节" }
        require(files.sumOf { it.bytes.toLong() } <= maxTotalBytes) { "附件总量最多 $maxTotalBytes 字节" }
    }

    companion object {
        fun from(capabilities: JsonObject?): AttachmentBudget {
            require(capabilities != null) { "尚未取得电脑端附件限制，请刷新后重试" }
            fun limit(name: String, fallback: Int): Int {
                val raw = capabilities[name] ?: return fallback
                val parsed = capabilities.text(name).toLongOrNull()
                require(parsed != null && parsed > 0) { "电脑端附件限制无效：$name=$raw" }
                return minOf(parsed, fallback.toLong()).toInt()
            }
            return AttachmentBudget(limit("attachmentsMaxFiles", 16), limit("attachmentsMaxFileBytes", 2 * 1024 * 1024),
                limit("attachmentsMaxBytes", 6 * 1024 * 1024), limit("attachmentsMaxRequestBytes", 12 * 1024 * 1024))
        }
    }
}
