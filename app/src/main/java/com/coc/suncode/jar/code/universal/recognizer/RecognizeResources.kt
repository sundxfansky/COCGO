package com.coc.suncode.jar.code.universal.recognizer

import com.coc.suncode.jar.code.universal.GameVersion
import com.coc.suncode.jar.code.universal.InGamesVars
import com.coc.suncode.jar.code.universal.smalltools.getStaticConfig
import com.coc.suncode.jar.ui.schema.Schema
import com.coc.suncode.core.util.basic.ShowMessage

/**
 * Data class representing the game's core resources.
 */
data class Resources(
    val gold: Int = 0,
    val elixir: Int = 0,
    val darkElixir: Int = 0
)

/**
 * Recognizes resources from the game screen.
 *
 * - When [isOpponent] is false (default), reads from the own-base area (1050, 26, 1245, 201).
 * - When [isOpponent] is true, reads from the opponent's area (50, 98, 293, 209).
 *
 * Resource identification logic:
 * - Smallest Y axis position is Gold.
 * - Next smallest is Elixir.
 * - Largest is Dark Elixir.
 *
 * @param isOpponent Whether to recognize the opponent's resources instead of own.
 * @return Recognized values, or null when the screenshot lacks readable resource counters.
 */
suspend fun recognizeResources(isOpponent: Boolean = false): Resources =
    recognizeResourcesOrNull(isOpponent) ?: Resources()

suspend fun recognizeResourcesOrNull(isOpponent: Boolean = false): Resources? {
    suspend fun readCounters(left: Int, top: Int, right: Int, bottom: Int): List<RecognizedText> =
        TextRecognizer.recognize(
            left, top, right, bottom, useChinese = false, threshold = if (isOpponent) 150 else 240
        ).filter { line -> line.text.any { it.isDigit() } }

    val results = if (!isOpponent) {
        readCounters(990, 20, 1270, 190)
    } else if (InGamesVars.currentGameVersion == GameVersion.GLOBAL) {
        // The current global opponent screen places loot above the usual rows.
        readCounters(50, 80, 200, 210).takeIf { it.size >= 2 }
            ?: readCounters(50, 98, 293, 209)
    } else {
        readCounters(50, 98, 293, 209)
    }

    val sortedResults = results.sortedBy { it.position?.top ?: Int.MAX_VALUE }
    if (getStaticConfig(Schema.GLOBAL_SETTINGS.DEBUG_MODE.key) == "1") {
        ShowMessage.record("资源识别（对手=$isOpponent）：${sortedResults.joinToString { it.text }}")
    }
    if (sortedResults.size < 2) return null

    var gold = 0
    var elixir = 0
    var darkElixir = 0

    sortedResults.forEachIndexed { index, recognizedText ->
        val cleanValue = extractValue(recognizedText.text)
        when (index) {
            0 -> gold = cleanValue
            1 -> elixir = cleanValue
            2 -> darkElixir = cleanValue
        }
    }

    return Resources(gold, elixir, darkElixir)
}

/**
 * Cleans the recognized text and extracts an Int value.
 * Removes spaces, commas, and any non-numeric characters.
 */
internal fun extractValue(text: String): Int {
    // Remove all non-digit characters and handle common misrecognitions
    val digitsOnly = text.replace("G", "6")
        .replace("o", "0").replace("O", "0")
        .replace("s", "5").replace("S", "5")
        .replace("z", "2").replace("Z", "2")
        .replace("I", "1").replace("l", "1")
        .replace(Regex("[^0-9]"), "")
    return digitsOnly.toIntOrNull() ?: 0
}
