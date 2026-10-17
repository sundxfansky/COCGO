package com.coc.suncode.jar.code.universal.recognizer

import android.graphics.Point
import com.coc.suncode.jar.code.colorschema.ColorSchema

// Finds a button by OCR-matching text within a screen region.
// Returns the matched text's center point in screen coordinates, or null if not found.
suspend fun findButtonByText(startX: Int, startY: Int, endX: Int, endY: Int, targetText: String): Point? {
    val results = TextRecognizer.recognize(startX, startY, endX, endY, useChinese = true)
    val match = results.firstOrNull { it.text.contains(targetText) } ?: return null
    val pos = match.position ?: return null
    return Point(startX + (pos.left + pos.right) / 2, startY + (pos.top + pos.bottom) / 2)
}

// All ColorSchema coordinates in this project are authored against this reference
// resolution, so clamping against it (rather than querying the live screen) keeps this
// function usable without threading a Bitmap/capture through just for bounds-checking.
private const val REFERENCE_SCREEN_WIDTH = 1280
private const val REFERENCE_SCREEN_HEIGHT = 720

// Convenience overload: expands a ColorSchema's (usually tight) color-sampling region by
// [padding] on each side so OCR has enough room to see the full button label, since
// color-matching regions are often sized for a small solid-color patch, not full text.
suspend fun findButtonByText(region: ColorSchema, targetText: String, padding: Int = 60): Point? {
    return findButtonByText(
        (region.x1 - padding).coerceAtLeast(0),
        (region.y1 - padding).coerceAtLeast(0),
        (region.x2 + padding).coerceAtMost(REFERENCE_SCREEN_WIDTH),
        (region.y2 + padding).coerceAtMost(REFERENCE_SCREEN_HEIGHT),
        targetText
    )
}
