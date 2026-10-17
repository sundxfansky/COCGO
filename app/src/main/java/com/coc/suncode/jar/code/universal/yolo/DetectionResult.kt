package com.coc.suncode.jar.code.universal.yolo

import android.graphics.RectF

/** A single object returned by the local YOLO model. */
data class DetectionResult(
    val boundingBox: RectF,
    val score: Float,
    val classIndex: Int
)
