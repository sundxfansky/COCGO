package com.coc.suncode.jar.code.universal.colors

import android.util.Log
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.system.screencapture.ScreenCaptureManager
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.core.util.basic.waitForPlay
import com.coc.suncode.core.util.fileactions.LogHelper.logAndRestart
import com.coc.suncode.jar.code.colorschema.BlockTemplateSchema
import com.coc.suncode.nativehelper.RustTools
import kotlinx.coroutines.delay

/** Result of a block template search. [x]/[y] is the template's top-left pixel. */
data class BlockTemplateMatch(val x: Int, val y: Int, val score: Double) {
    /** Center of the matched area, convenient for tapping. */
    fun center(schema: BlockTemplateSchema): Pair<Int, Int> = Pair(x + schema.width / 2, y + schema.height / 2)
}

/**
 * Finds the position inside the schema's search area where the block template fits best.
 * Only matches whose similarity reaches [BlockTemplateSchema.similarity] are returned.
 * @param byteBuffer A screenshot to reuse. If null, a new screenshot will be captured.
 */
suspend fun findBlockTemplate(
    schema: BlockTemplateSchema,
    byteBuffer: ScreenCaptureManager.CaptureResult? = null
): BlockTemplateMatch? {
    waitForPlay()

    val capture = byteBuffer ?: (ScreenCaptureManager.capture(asBitmap = false) as? ScreenCaptureManager.CaptureResult)
    if (capture == null) {
        ShowMessage.record("截图失败，无法进行图片匹配", Log.ERROR)
        return null
    }

    // The template image is decoded once and cached; a missing image has already been logged.
    val template = schema.template ?: return null

    try {
        val result = RustTools.findBlockTemplateRaw(
            capture.buffer,
            capture.width, capture.height, capture.rowStride,
            schema.x1, schema.y1, schema.x2, schema.y2,
            schema.block, template.cols, template.rows,
            template.means,
            schema.similarity
        )
        if (result != null && result.size == 3) {
            // Native side returns the score multiplied by 10000.
            val match = BlockTemplateMatch(result[0], result[1], result[2] / 10000.0)
            schema.name?.let { ShowMessage("已找到图片：$it,坐标：${match.x}, ${match.y},相似度：${"%.4f".format(match.score)}") }
            return match
        }
    } catch (e: Exception) {
        ShowMessage.record("Failed to find block template", Log.ERROR, throwable = e)
    }
    return null
}

/**
 * Repeatedly calls [findBlockTemplate] for each schema until a match is found or the duration expires.
 */
suspend fun findBlockTemplateUntil(
    schemas: List<BlockTemplateSchema>,
    duration: Int
): BlockTemplateMatch? {
    val startTime = System.currentTimeMillis()
    val multiplier = GlobalVars.configStates["delay_multiplier"]?.value?.toFloat()
        ?: logAndRestart("Failed to get delayMultiplier")

    while (true) {
        // Share one screenshot between all schemas of the same round.
        val captured = ScreenCaptureManager.capture(asBitmap = false) as? ScreenCaptureManager.CaptureResult
        for (schema in schemas) {
            val result = findBlockTemplate(schema, captured)
            if (result != null) return result
        }
        if (System.currentTimeMillis() - startTime >= duration * multiplier) break
        delay(100)
    }
    return null
}
