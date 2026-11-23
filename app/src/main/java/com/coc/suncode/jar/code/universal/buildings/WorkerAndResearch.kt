package com.coc.suncode.jar.code.universal.buildings

import android.graphics.Point
import com.coc.suncode.jar.code.universal.GameVersion
import com.coc.suncode.jar.code.universal.InGamesVars
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import com.coc.suncode.core.system.screencapture.ScreenCaptureManager
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.core.util.bugreporter.BugReporter
import com.coc.suncode.jar.code.colorschema.ColorSchema
import com.coc.suncode.jar.code.colorschema.MyColors
import com.coc.suncode.jar.code.universal.colors.findMultiColors
import com.coc.suncode.jar.code.universal.colors.findMultiColorsUntil
import com.coc.suncode.jar.code.universal.enterMainScreen
import com.coc.suncode.jar.code.universal.recognizer.TextRecognizer

/**
 * Data class representing the worker information (available/total).
 */
data class WorkerInfo(val available: Int, val total: Int)

enum class BaseType {
    Builder, Main
}

/**
 * Unified worker and research detection logic for both Main Base and Builder Base.
 * Dispatches on [BaseType] to handle base-specific differences (color schemas, goblin checks, messages).
 */
object WorkerAndResearch {
    // Detect the current layout first so legacy global accounts can still use the top bar.
    suspend fun findUpgradeEntry(baseType: BaseType): Point? {
        val oldIcons = when (baseType) {
            BaseType.Main -> MyColors.MainBaseWorkerSchemas
            BaseType.Builder -> MyColors.BuilderBaseWorkerSchemas
        }
        val icons = if (InGamesVars.currentGameVersion == GameVersion.GLOBAL) {
            listOf(if (baseType == BaseType.Main) MyColors.MainBaseBuildingUpgradeEntryGlobal else MyColors.NewBuilderBaseWorker) + oldIcons
        } else {
            oldIcons
        }
        return findMultiColorsUntil(schemas = icons, duration = 1000)
    }


    /**
     * Detects the number of workers for the given base type.
     * @return A [WorkerInfo] object. If detection fails, returns (0, 0).
     */
    suspend fun detectWorkerNumber(baseType: BaseType): WorkerInfo {
        // Must double-check to wait for a little bit, otherwise the detection will fail.
        if (!enterMainScreen(true)) return WorkerInfo(0, 0)
        if (InGamesVars.currentGameVersion == GameVersion.GLOBAL) {
            val icon = if (baseType == BaseType.Main) MyColors.MainBaseWorker6 else MyColors.NewBuilderBaseWorker
            if (findMultiColors(icon, increment = 1) != null) return detectGlobalWorkers(baseType)
        }
        // The regular counter does not include the goblin worker.
        if (baseType == BaseType.Main && listOf(MyColors.GoblinWorker, MyColors.GoblinWorker2).any { findMultiColors(it) != null }) {
            return WorkerInfo(0, 6)
        }

        val workerSchemas = when (baseType) {
            BaseType.Builder -> MyColors.BuilderBaseWorkerSchemas
            BaseType.Main -> MyColors.MainBaseWorkerSchemas
        }
        val baseName = when (baseType) {
            BaseType.Builder -> "夜世界"; BaseType.Main -> "主世界"
        }
        val bugTag = when (baseType) {
            BaseType.Builder -> "Builder_Base_Worker"; BaseType.Main -> "Main_Base_Worker"
        }

        val worker = findMultiColorsUntil(schemas = workerSchemas, duration = 200)
        if (worker != null) {
            // Define the crop region for the worker number text
            val startX = (worker.x - 50).coerceAtLeast(0)
            val startY = 0
            val endX = (worker.x + 250).coerceAtMost(1280)
            val endY = 70

            val results = TextRecognizer.recognize(startX, startY, endX, endY, useChinese = false, applyPreprocess = true, threshold = 240)

            // Sort by x-axis position to ensure left-to-right reading order
            val combinedText = results.sortedBy { it.position?.left ?: 0 }.joinToString("") { it.text }
            val workerInfo = parseWorkerInfo(combinedText)
            if (workerInfo != WorkerInfo(0, 0)) return workerInfo
            // Fallback: OCR failed, use color-based pattern matching on the preprocessed bitmap
            return detectWorkerByColorMatch(startX, startY, endX, endY)
        }
        ShowMessage("未检测到${baseName}工人，已将错误截图保存到/sdcard/sunFiles/bugReporter\n请反馈给作者")
        BugReporter.takeScreenshot("${bugTag}_Not_Detected")
        return WorkerInfo(0, 0)
    }

    private suspend fun detectGlobalWorkers(baseType: BaseType): WorkerInfo {
        val results = TextRecognizer.recognizeSingleLine(1186, 531, 1247, 572)
        val recognized = parseWorkerInfo(results)
        if (recognized.total > 0) return recognized

        // The Builder Base counter may show a single available-worker digit.
        if (baseType == BaseType.Builder) {
            return detectWorkerByColorMatch(1186, 531, 1247, 572, useGlobalDigit = true)
        }
        return WorkerInfo(0, 0)
    }

    /**
     * Detects if research is available for the given base type.
     * @return true if research is available, false otherwise.
     */
    suspend fun detectResearch(baseType: BaseType): Boolean {
        // Must double-check to wait for a little bit, otherwise the detection will fail.
        if (!enterMainScreen(true)) return false
        val baseName = when (baseType) {
            BaseType.Builder -> "夜世界"; BaseType.Main -> "主世界"
        }
        val bugTag = when (baseType) {
            BaseType.Builder -> "Builder_Base_Research"; BaseType.Main -> "Main_Base_Research"
        }

        // MainBase-only: goblin researcher pre-check
        if (baseType == BaseType.Main && findMultiColors(schema = MyColors.GoblinResearcher) != null) {
            ShowMessage("检测到哥布林研究人员，无法进行研究")
            return false
        }

        // Detect research icon and count researchers
        // Fallback to ResearchIcon2 if ResearchIcon is not found
        val research = findMultiColors(schema = MyColors.ResearchIcon)
            ?: findMultiColors(schema = MyColors.ResearchIcon2)
        if (research != null) {
            // Define the crop region for the researcher number text
            val startX = (research.x - 100).coerceAtLeast(0)
            val startY = 0
            val endX = (research.x + 200).coerceAtMost(1280)
            val endY = 70

            val results = TextRecognizer.recognize(startX, startY, endX, endY, useChinese = false, applyPreprocess = true, threshold = 240)
            // Sort by x-axis position to ensure left-to-right reading order
            val combinedText = results.sortedBy { it.position?.left ?: 0 }.joinToString("") { it.text }
            val researcherInfo = parseWorkerInfo(combinedText)
            if (researcherInfo != WorkerInfo(0, 0)) {
                ShowMessage("${baseName}研究数量：${researcherInfo.available}/${researcherInfo.total}")
                return researcherInfo.available > 0
            }

            // Fallback: OCR failed, use color-based pattern matching
            val fallback = detectWorkerByColorMatch(startX, startY, endX, endY)
            if (fallback != WorkerInfo(0, 0)) {
                ShowMessage("${baseName}研究数量：${fallback.available}/${fallback.total}")
                return fallback.available > 0
            }
            ShowMessage("${baseName}研究数量：0/0")
            return false
        }
        ShowMessage("未检测到${baseName}研究，已将错误截图保存到/sdcard/sunFiles/bugReporter\n请反馈给作者")
        BugReporter.takeScreenshot("${bugTag}_Not_Detected")
        return false
    }

    /**
     * Fallback detection: use findMultiColors on a preprocessed (binarized) bitmap
     * to match BinarySlash and BinaryOne patterns when OCR fails.
     */
    private suspend fun detectWorkerByColorMatch(
        startX: Int, startY: Int, endX: Int, endY: Int, useGlobalDigit: Boolean = false
    ): WorkerInfo {
        val screenBuffer = ScreenCaptureManager.capture(asBitmap = true) as? Bitmap
            ?: return WorkerInfo(0, 0)

        try {
            if (startX < 0 || startY < 0 || endX > screenBuffer.width || endY > screenBuffer.height) {
                return WorkerInfo(0, 0)
            }
            val crop = Bitmap.createBitmap(screenBuffer, startX, startY, endX - startX, endY - startY)
            val preprocessed = TextRecognizer.preprocess(crop, if (useGlobalDigit) 235 else 240, true)
            try {
                if (useGlobalDigit) {
                    val one = ColorSchema.rescope(MyColors.BinaryOne3, 0, 0, crop.width, crop.height)
                    return if (findMultiColors(bitmap = preprocessed, schema = one, increment = 1) != null) WorkerInfo(1, 2) else WorkerInfo(0, 0)
                }
                return matchWorkerFraction(preprocessed)
            } finally {
                preprocessed.recycle()
                if (crop !== screenBuffer) crop.recycle()
            }
        } finally {
            screenBuffer.recycle()
        }
    }

    private suspend fun matchWorkerFraction(preprocessed: Bitmap): WorkerInfo {
        val cropWidth = preprocessed.width
        val cropHeight = preprocessed.height
        // Step 1: find the slash character "/" in the preprocessed bitmap
        val slashSchema = ColorSchema.rescope(MyColors.BinarySlash, 0, 0, cropWidth, cropHeight)
        val slashPoint = findMultiColors(bitmap = preprocessed, schema = slashSchema)
            ?: return WorkerInfo(0, 0)
        val zeroSchema = ColorSchema.rescope(MyColors.BinaryZero, 0, 0, slashPoint.x, cropHeight)
        val zeroPoint = findMultiColors(bitmap = preprocessed, schema = zeroSchema)
        if (zeroPoint != null) return WorkerInfo(0, 0)
        // Step 2: find the digit "1" to the left of the slash
        val oneSchema = ColorSchema.rescope(MyColors.BinaryOne, 0, 0, slashPoint.x, cropHeight)
        val onePoint = findMultiColors(bitmap = preprocessed, schema = oneSchema)
        if (onePoint != null) {
            return WorkerInfo(1, 1)
        }

        return WorkerInfo(0, 0)
    }

    /**
     * Parses the recognized text into WorkerInfo.
     */
    internal fun parseWorkerInfo(text: String): WorkerInfo {
        // Clean text and handle common OCR misrecognitions
        val cleaned = text.replace(" ", "").replace("o", "0").replace("O", "0").replace("I", "1").replace("l", "1")
            .replace("Z", "2").replace("z", "2").replace("S", "5").replace("s", "5").replace("G", "6")

        val match = Regex("""(\d+)/(\d+)""").find(cleaned)

        if (match != null) {
            var (availableStr, totalStr) = match.destructured

            // Filter: If xx/xx, keep the last digit of the first part and the first digit of the second part
            // For example: 12/53 -> 2/5, 1/22 -> 1/2
            if (availableStr.length > 1) {
                availableStr = availableStr.last().toString()
            }
            if (totalStr.length > 1) {
                totalStr = totalStr.first().toString()
            }

            val available = availableStr.toInt()
            val total = totalStr.toInt()

            // Swap if available > total, since total must always be >= available
            if (available > total) {
                return WorkerInfo(total, available)
            }
            return WorkerInfo(available, total)
        }
        return WorkerInfo(0, 0)
    }
}
