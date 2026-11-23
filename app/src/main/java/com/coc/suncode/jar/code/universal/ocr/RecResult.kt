package com.coc.suncode.jar.code.universal.ocr

/**
 * Text recognition result produced by [PpOcrEngine] without detection.
 *
 * @param text recognized text string
 * @param confidence recognition confidence (mean CTC score of recognized characters, 0..1)
 */
data class RecResult(
    val text: String,
    val confidence: Float
)
