package com.pilot.mobile.transport

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Random
import java.util.zip.GZIPOutputStream
import kotlin.test.*

class FrameCodecTest {
    @Test fun `中文数据可压缩且解码逐字节一致`() {
        val source = "工作台包含中文与 emoji 🌏，未变化时不重复下载。".repeat(300).toByteArray()
        val encoded = FrameCodec.encode(source)
        assertTrue(encoded.size < source.size / 10)
        assertContentEquals(source, FrameCodec.decode(encoded, "gzip"))
    }

    @Test fun `空帧与不可压缩的上限帧可以往返`() {
        for (source in listOf(byteArrayOf(), ByteArray(FrameCodec.MAX_DECODED_FRAME).also { Random(7).nextBytes(it) })) {
            val encoded = FrameCodec.encode(source)
            assertTrue(encoded.size <= FrameCodec.MAX_ENCODED_FRAME)
            assertContentEquals(source, FrameCodec.decode(encoded, "gzip"))
        }
        assertFailsWith<IllegalArgumentException> { FrameCodec.encode(ByteArray(FrameCodec.MAX_DECODED_FRAME + 1)) }
    }

    @Test fun `拒绝损坏截断与未知编码但保留旧版明文`() {
        val source = "兼容旧客户端".toByteArray()
        assertSame(source, FrameCodec.decode(source, ""))
        assertFailsWith<IOException> { FrameCodec.decode(source, "br") }
        assertFailsWith<IOException> { FrameCodec.decode(source, "gzip") }
        val encoded = FrameCodec.encode(source)
        assertFailsWith<IOException> { FrameCodec.decode(encoded.copyOf(encoded.size - 4), "gzip") }
        encoded[encoded.lastIndex - 7] = (encoded[encoded.lastIndex - 7].toInt() xor 1).toByte()
        assertFailsWith<IOException> { FrameCodec.decode(encoded, "gzip") }
    }

    @Test fun `限制压缩输入及解压膨胀避免绕过内存上限`() {
        assertFailsWith<IOException> { FrameCodec.decode(ByteArray(FrameCodec.MAX_ENCODED_FRAME + 1), "gzip") }
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(ByteArray(FrameCodec.MAX_DECODED_FRAME + 1)) }
        assertFailsWith<IOException> { FrameCodec.decode(output.toByteArray(), "gzip") }
    }
}
