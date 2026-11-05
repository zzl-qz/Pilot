package com.pilot.mobile.bridge

import com.google.zxing.*
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** 个别高密度码的默认掩码会干扰定位；校验实际 PNG，换掩码时保持配对凭证不变。 */
internal object PairingQr {
    fun png(code: String): ByteArray {
        val decodeHints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
        for (mask in -1..7) {
            val hints = mutableMapOf<EncodeHintType, Any>(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4)
            if (mask >= 0) hints[EncodeHintType.QR_MASK_PATTERN] = mask
            val matrix = MultiFormatWriter().encode(code, BarcodeFormat.QR_CODE, 768, 768, hints)
            val bytes = ByteArrayOutputStream().also { MatrixToImageWriter.writeToStream(matrix, "PNG", it) }.toByteArray()
            val bitmap = BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(ImageIO.read(bytes.inputStream()))))
            val decoded = try { MultiFormatReader().decode(bitmap, decodeHints).text } catch (_: ReaderException) { null }
            if (decoded == code) return bytes
        }
        error("二维码暂时无法可靠识别，请重新生成配对码")
    }
}
