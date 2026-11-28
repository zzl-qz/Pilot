package com.pilot.mobile

import com.pilot.mobile.transport.long
import kotlinx.serialization.json.JsonObject

/** 服务端配置与手机传输上限的交集；旧电脑端未声明时保留原有手机上限。 */
internal data class AttachmentBudget(val maxFiles: Int, val maxFileBytes: Int, val maxTotalBytes: Int, val maxRequestBytes: Int) {
    fun validate(files: List<MobileAttachment>) {
        require(files.size <= maxFiles) { "一次最多 $maxFiles 个附件" }
        require(files.all { it.bytes in 1..maxFileBytes }) { "单个附件需大于 0 且不超过 ${describe(maxFileBytes)}" }
        require(files.sumOf { it.bytes.toLong() } <= maxTotalBytes) { "附件总量最多 ${describe(maxTotalBytes)}" }
    }

    companion object {
        /** 提示同时给出可读大小与字节数：界面看得懂，测试和排障能核对能力值。 */
        fun describe(bytes: Int): String {
            val human = when {
                bytes >= 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
                bytes >= 1024 -> "${Math.round(bytes / 1024.0)} KB"
                else -> "$bytes B"
            }
            return "$human（$bytes 字节）"
        }

        fun from(capabilities: JsonObject?): AttachmentBudget {
            require(capabilities != null) { "尚未取得电脑端附件限制，请刷新后重试" }
            fun limit(name: String, fallback: Int): Int {
                val raw = capabilities[name] ?: return fallback
                val parsed = capabilities.long(name)
                require(parsed != null && parsed > 0) { "电脑端附件限制无效：$name=$raw" }
                return minOf(parsed, fallback.toLong()).toInt()
            }
            return AttachmentBudget(limit("attachmentsMaxFiles", 16), limit("attachmentsMaxFileBytes", 2 * 1024 * 1024),
                limit("attachmentsMaxBytes", 6 * 1024 * 1024), limit("attachmentsMaxRequestBytes", 12 * 1024 * 1024))
        }
    }
}
