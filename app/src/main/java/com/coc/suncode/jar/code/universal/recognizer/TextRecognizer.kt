package com.coc.suncode.jar.code.universal.recognizer

import android.graphics.Bitmap
import android.graphics.Rect
import com.coc.suncode.core.system.screencapture.ScreenCaptureManager
import com.coc.suncode.core.util.basic.waitForPlay
import com.coc.suncode.core.util.fileactions.LogHelper.showDebugInfo
import com.coc.suncode.core.util.basic.ShowMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.graphics.createBitmap
import java.io.File
import java.io.FileOutputStream
import com.coc.suncode.jar.code.universal.ocr.PpOcrEngine
import kotlin.coroutines.cancellation.CancellationException

data class RecognizedText(
    val text: String,
    val position: Rect?
)

object TextRecognizer {

    suspend fun recognize(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        useChinese: Boolean = true,
        threshold: Int = 140,
        saveImage: Boolean = false,
        applyPreprocess: Boolean = true,
        invertBinarization: Boolean = true
    ): List<RecognizedText> {
        val screenBuffer = ScreenCaptureManager.capture(asBitmap = true) as? Bitmap
        if (screenBuffer == null) {
            // OCR is a best-effort helper. A transient capture failure must not force-stop the
            // host process and lose the user's current automation state.
            ShowMessage.record("OCR skipped: screen capture returned no bitmap")
            showDebugInfo("OCR skipped: screen capture returned no bitmap")
            return emptyList()
        }

        val width = endX - startX
        val height = endY - startY

        if (startX < 0 || startY < 0 || width <= 0 || height <= 0 ||
            endX > screenBuffer.width || endY > screenBuffer.height
        ) {
            ShowMessage.record(
                "OCR skipped: invalid crop area ($startX,$startY)-($endX,$endY) for " +
                    "${screenBuffer.width}x${screenBuffer.height}"
            )
            screenBuffer.recycle()
            return emptyList()
        }

        try {
            waitForPlay()
            // 1. Crop the original region. The caller owns the screen frame; release both
            // bitmaps after inference to avoid a native heap leak during repeated OCR calls.
            val croppedBitmap = Bitmap.createBitmap(screenBuffer, startX, startY, width, height)
            try {

                // 2. [Core Optimization] Apply preprocessing (optional)
                // PP-OCR performs its own detector/recognizer preprocessing; preserve RGB input here.
                val bitmapToRecognize = croppedBitmap

                // 2.1 Save the image if requested
                if (saveImage) {
                    ScreenCaptureManager.getContext()?.let { context ->
                        try {
                            val file = File(context.filesDir, "test.png")
                            FileOutputStream(file).use { out ->
                                bitmapToRecognize.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            showDebugInfo("Image saved to: ${file.absolutePath}")
                        } catch (e: Exception) {
                            showDebugInfo("Failed to save image: ${e.message}")
                        }
                    }
                }

                // 3. Recognize the image
                return recognizeTextSync(bitmapToRecognize, useChinese)
            } finally {
                if (!croppedBitmap.isRecycled) croppedBitmap.recycle()
            }
        } catch (e: CancellationException) {
            // Important: if it's a cancellation exception, must rethrow it for coroutine system to handle
            throw e
        } catch (e: Exception) {
            // OCR is an optional recognition step; keep the automation alive and let the
            // caller use its existing color/template fallback when model inference fails.
            ShowMessage.record("OCR recognition failed; using fallback: ${e.message}", throwable = e)
            showDebugInfo("OCR recognition failed; using fallback: ${e.message}")
            return emptyList()
        } finally {
            if (!screenBuffer.isRecycled) screenBuffer.recycle()
        }
    }

    /**
     * Image preprocessing: grayscale + binarization
     * Eliminates background interference, making it easier for ML Kit to recognize text contours
     */
    internal fun preprocess(src: Bitmap, threshold: Int, invertBinarization: Boolean): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val color = pixels[i]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF

            // Grayscale conversion formula
            val gray = (r * 0.299 + g * 0.587 + b * 0.114).toInt()

            // Binarization: swap foreground/background when invertBinarization is true
            pixels[i] = if (gray > threshold) {
                if (invertBinarization) -0x1000000 else -0x1
            } else {
                if (invertBinarization) -0x1 else -0x1000000
            }
        }

        val out = createBitmap(width, height)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }

    private suspend fun recognizeTextSync(bitmap: Bitmap, useChinese: Boolean): List<RecognizedText> =
        withContext(Dispatchers.IO) {
            // Use the bundled PP-OCR engine so recognition works without ML Kit or a remote service.
            ScreenCaptureManager.getContext()?.let { PpOcrEngine.initialize(it) }
            return@withContext PpOcrEngine.recognize(bitmap).map {
                RecognizedText(it.text, Rect(it.box.left.toInt(), it.box.top.toInt(), it.box.right.toInt(), it.box.bottom.toInt()))
            }
        }
}
