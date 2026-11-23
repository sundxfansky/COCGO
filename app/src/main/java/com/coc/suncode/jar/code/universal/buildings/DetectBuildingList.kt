package com.coc.suncode.jar.code.universal.buildings

import com.coc.suncode.jar.code.universal.GameVersion
import com.coc.suncode.jar.code.universal.InGamesVars
import com.coc.suncode.jar.code.colorschema.MyColors
import com.coc.suncode.jar.code.universal.colors.findMultiColors
import com.coc.suncode.core.system.screencapture.ScreenCaptureManager
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.core.util.basic.delayWithMultiplier
import com.coc.suncode.core.util.fileactions.LogHelper.logAndRestart
import com.coc.suncode.core.util.touchactions.TouchActions
import com.coc.suncode.jar.code.universal.recognizer.TextRecognizer

/**
 * Detects building names from the screen.
 *
 * Scans the building-list area for Chinese building names.
 *
 * @param excludeNewBuildings If true, new buildings (新-prefixed) are excluded from the result.
 * @return A list of [DetectedBuilding] objects.
 */
private suspend fun hasGlobalBuildingList(): Boolean =
    InGamesVars.currentGameVersion == GameVersion.GLOBAL && findMultiColors(MyColors.NewUpgradeList) != null

suspend fun detectBuildingList(excludeNewBuildings: Boolean = false): BuildingDetectionResult =
    detectBuildingList(excludeNewBuildings, hasGlobalBuildingList())

private suspend fun detectBuildingList(excludeNewBuildings: Boolean, hasSidePanel: Boolean): BuildingDetectionResult {
    val (startX, startY, endX, endY) = if (hasSidePanel) listOf(992, 46, 1140, 640) else listOf(400, 100, 900, 560)

    // Recognize text in the specified area
    val results = TextRecognizer.recognize(startX, startY, endX, endY, useChinese = true, threshold = 160)

    if (results.isEmpty()) return BuildingDetectionResult(emptyList())

    // Take a screenshot for color checking (needed for post-process and later filtering)
    val screenBuffer = ScreenCaptureManager.capture(asBitmap = false) as? ScreenCaptureManager.CaptureResult
        ?: logAndRestart("failed to take screenshot at building detection")

    // Post-process: Check for green indicator (new building marker)
    // If green pixels > 40 in area (x-15, y-3, x, y+20), add "新" prefix
    val processedResults = results.map { item ->
        val pos = item.position ?: return@map item
        val x = pos.left + startX
        val y = pos.top + startY

        val greenCount = countGreenPixelsInArea(screenBuffer, x - 15, y - 3, x, y + 20)
        if (greenCount >20 && !item.text.startsWith("新")) {
            item.copy(text = "新${item.text}")
        } else {
            item
        }
    }

    // If "建议升级" is detected, only keep results below it (greater y)
    val upgradeY = processedResults.mapNotNull { item ->
        val pos = item.position ?: return@mapNotNull null
        val cleaned = cleanBuildingName(item.text)
        if (cleaned == "建议升级") {
            pos.top + startY
        } else null
    }.minOrNull()

    // Filter, clean, and return building names with positions
    // For each detected text, exclude it if more than 10 pixels of FF887F (RGB) are found in the specified area
    // Map results to DetectedBuilding and a flag indicating if it was marked as "New"
    val allBuildings = processedResults.mapNotNull { item ->
        val pos = item.position ?: return@mapNotNull null
        if (!chineseRegex.containsMatchIn(item.text)) return@mapNotNull null

        // Absolute position to the screen
        val x = pos.left + startX
        val y = pos.top + startY
        if (upgradeY != null && y <= upgradeY) return@mapNotNull null

        val costStartX = if (hasSidePanel) 1138 else x + 200
        val costEndX = if (hasSidePanel) 1279 else x + 430
        val count = countPixelsInArea(screenBuffer, costStartX, y - 20, costEndX, y + 10, 0xFF887F)
        if (count > 10) return@mapNotNull null

        val cleanedName = cleanBuildingName(item.text)
        val isNew = cleanedName.startsWith("新")

        if (isNew || cleanedName in ALL_BUILDINGS) {
            DetectedBuilding(name = cleanedName, x = x, y = y) to isNew
        } else {
            null
        }
    }

    // When excludeNewBuildings is true, filter out new buildings and return only existing ones
    if (excludeNewBuildings) {
        val existingBuildings = allBuildings.filter { !it.second }.map { it.first }
        return BuildingDetectionResult(existingBuildings)
    }

    // Filter to get only the buildings marked as "New"
    val newBuildings = allBuildings.filter { it.second }.map { it.first }

    // If any "New" building is detected, return only those
    if (newBuildings.isNotEmpty()) {
        return BuildingDetectionResult(newBuildings)
    }

    return BuildingDetectionResult(allBuildings.map { it.first })
}

/**
 * Iterates through the building upgrade list in Builder Base.
 * Performs swipes and calls [onDetect] for each detection result.
 * If [onDetect] returns true, the iteration stops immediately.
 */
suspend fun iterateBuilderBaseBuildingUpgradeList(
    excludeNewBuildings: Boolean = false,
    onDetect: suspend (BuildingDetectionResult) -> Boolean
) {
    val hasSidePanel = hasGlobalBuildingList()
    val swipeX = if (hasSidePanel) 1070 else 666
    var previousBuildingNames: List<String>? = null
    loop@ for (i in 1..12) {
        val result = detectBuildingList(excludeNewBuildings, hasSidePanel)
        if (onDetect(result)) return

        val currentBuildingNames = result.buildings.map { it.name }.sorted()
        if (previousBuildingNames != null && currentBuildingNames == previousBuildingNames) {
            repeat(2) {
                TouchActions.swipe(swipeX, 170, swipeX, 300, delayTime = 600)
                delayWithMultiplier(200)
                val extraResult = detectBuildingList(excludeNewBuildings, hasSidePanel)
                if (onDetect(extraResult)) return
            }
            break@loop
        }
        previousBuildingNames = currentBuildingNames
        TouchActions.swipe(swipeX, 500, swipeX, 120, delayTime = 600)
        delayWithMultiplier(200)
    }
}
