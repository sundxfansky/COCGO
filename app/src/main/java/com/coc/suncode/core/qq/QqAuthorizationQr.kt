package com.coc.suncode.core.qq

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.coc.suncode.core.system.screencapture.ScreenCaptureManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.ReaderException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import java.io.ByteArrayOutputStream
import java.net.URI

/** Never send screen pixels: rebuild a QR image from a decoded Tencent login URL. */
internal object QqAuthorizationQr {
    suspend fun capture(): String? {
        val screenshot = ScreenCaptureManager.captureFeedbackBitmap() ?: return null
        val size = maxOf(screenshot.width, screenshot.height)
        val scale = minOf(1.0, 1280.0 / size)
        val frame = Bitmap.createScaledBitmap(
            screenshot, (screenshot.width * scale).toInt().coerceAtLeast(1),
            (screenshot.height * scale).toInt().coerceAtLeast(1), false
        )
        val url = try {
            decode(frame)
        } finally {
            if (frame !== screenshot) frame.recycle()
            screenshot.recycle()
        } ?: return null

        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 256, 256)
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) Color.BLACK else Color.WHITE
        }
        val qr = Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { output ->
                if (qr.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                } else null
            }
        } finally {
            qr.recycle()
        }
    }

    private fun decode(frame: Bitmap): String? {
        val pixels = IntArray(frame.width * frame.height)
        frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(frame.width, frame.height, pixels)))
        val hints = mapOf(DecodeHintType.TRY_HARDER to true)
        val candidates = try {
            QRCodeMultiReader().decodeMultiple(bitmap, hints)
        } catch (_: NotFoundException) {
            emptyArray()
        }
        val match = candidates.firstNotNullOfOrNull { candidate ->
            candidate.text?.takeIf(::isQqLoginUrl)
        }
        if (match != null) return match
        return try {
            QRCodeReader().decode(bitmap, hints).text?.takeIf(::isQqLoginUrl)
        } catch (_: ReaderException) {
            null
        }
    }

    internal fun isQqLoginUrl(value: String): Boolean {
        if (value.length > 2048) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (uri.userInfo != null || uri.port != -1) return false
        return when {
            uri.scheme.equals("https", ignoreCase = true) -> host == "qq.com" || host.endsWith(".qq.com")
            // The signed companion displays Tencent's short QR URL with an HTTP scheme.
            uri.scheme.equals("http", ignoreCase = true) -> host == "txz.qq.com"
            else -> false
        }
    }
}
