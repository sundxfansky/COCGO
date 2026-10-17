package com.coc.suncode.jar.code.universal.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.jar.code.universal.yolo.local.LocalYoloEngine
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrRuntimeTest {
    @Test
    fun bundledModelsRecognizeTextAfterClearAndReload() = withRuntime { bitmap ->
        PpOcrEngine.load()
        assertTrue("Both OCR sessions and the dictionary must load", PpOcrEngine.isLoaded)
        assertRecognizesText(bitmap)

        PpOcrEngine.clear()
        assertFalse("Clearing OCR must release its model sessions", PpOcrEngine.isLoaded)

        PpOcrEngine.load()
        assertTrue("OCR must load again after being cleared", PpOcrEngine.isLoaded)
        assertRecognizesText(bitmap)
    }

    @Test
    fun ocrRemainsUsableWhenYoloNumbersModelsAreLoadedAndCleared() = withRuntime { bitmap ->
        PpOcrEngine.load()
        assertRecognizesText(bitmap)

        LocalYoloEngine.loadWeights("numbers")
        assertTrue("The bundled YOLO numbers model must load", LocalYoloEngine.isModelLoaded())
        // Synthetic text is not a YOLO accuracy fixture; inference must still complete.
        LocalYoloEngine.detect(bitmap, clearWeightsAfter = true)
        assertFalse("YOLO must honor clearWeightsAfter", LocalYoloEngine.isModelLoaded())
        assertTrue("Closing YOLO must preserve the active OCR sessions", PpOcrEngine.isLoaded)
        assertRecognizesText(bitmap)

        PpOcrEngine.clear()
        LocalYoloEngine.loadWeights("numbers")
        LocalYoloEngine.detect(bitmap, clearWeightsAfter = false)
        assertTrue("YOLO must retain weights when requested", LocalYoloEngine.isModelLoaded())
        LocalYoloEngine.clearWeights()
        assertFalse("Explicit YOLO cleanup must release its session", LocalYoloEngine.isModelLoaded())

        PpOcrEngine.load()
        assertRecognizesText(bitmap)
    }

    private fun assertRecognizesText(bitmap: Bitmap) {
        val results = PpOcrEngine.recognize(bitmap)
        assertTrue("OCR must detect text in the generated image", results.isNotEmpty())
        val text = results.joinToString("") { it.text }
            .filterNot { it.isWhitespace() }
            .uppercase(Locale.ROOT)
        assertTrue(
            "Expected HELLO or 12345 in OCR output, received: $text",
            text.contains("HELLO") || text.contains("12345")
        )
        assertFalse("OCR must not recycle its caller's bitmap", bitmap.isRecycled)
    }

    private fun withRuntime(block: (Bitmap) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previousLogVisibility = ShowMessage.isLogWindowVisible()
        val bitmap = createTextBitmap()
        try {
            // Exercise the engines directly without launching activities or overlay services.
            ShowMessage.setLogOverlayVisible(true)
            PpOcrEngine.clear()
            LocalYoloEngine.clearWeights()
            PpOcrEngine.initialize(context)
            LocalYoloEngine.initialize(context)
            block(bitmap)
        } finally {
            try {
                PpOcrEngine.clear()
            } finally {
                try {
                    LocalYoloEngine.clearWeights()
                } finally {
                    bitmap.recycle()
                    ShowMessage.setLogOverlayVisible(previousLogVisibility)
                }
            }
        }
    }

    private fun createTextBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(960, 240, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 84f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val baseline = (bitmap.height - paint.fontMetrics.ascent - paint.fontMetrics.descent) / 2f
        canvas.drawText("HELLO 12345", bitmap.width / 2f, baseline, paint)
        return bitmap
    }
}
