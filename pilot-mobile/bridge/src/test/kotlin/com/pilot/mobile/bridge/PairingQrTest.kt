package com.pilot.mobile.bridge

import com.google.zxing.*
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.pilot.mobile.transport.PairingInvite
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.*

class PairingQrTest {
    @Test fun `固定种子的高密度配对码轮换后实际PNG均能被扫码配置识别`() {
        val random = Random(20261105)
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        repeat(96) {
            val ticket = (0 until 160 + it % 6 * 80).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
            val code = PairingInvite(ticket = ticket, secret = "a".repeat(43), name = "测试电脑", expiresAt = 1800000000000).encode()
            val bitmap = BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(ImageIO.read(PairingQr.png(code).inputStream()))))
            val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
            assertEquals(code, MultiFormatReader().decode(bitmap, hints).text)
        }
    }
}
