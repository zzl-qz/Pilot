package com.pilot.mobile.transport

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** 每个数据帧独立压缩，流式响应无需整体缓冲；旧客户端未声明能力时仍收明文帧。 */
object FrameCodec {
    const val MAX_DECODED_FRAME = 64 * 1024
    const val MAX_ENCODED_FRAME = MAX_DECODED_FRAME + 1024

    fun encode(bytes: ByteArray): ByteArray {
        require(bytes.size <= MAX_DECODED_FRAME) { "待压缩的数据帧过大" }
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray, encoding: String): ByteArray {
        if (encoding.isEmpty()) return bytes
        if (encoding != "gzip") throw IOException("连接组件返回不支持的数据编码，请更新电脑和手机端")
        if (bytes.size > MAX_ENCODED_FRAME) throw IOException("压缩数据帧超过上限")
        val output = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                if (output.size() + size > MAX_DECODED_FRAME) throw IOException("解压后的数据帧超过上限")
                output.write(buffer, 0, size)
            }
        }
        return output.toByteArray()
    }
}
