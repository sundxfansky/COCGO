package com.coc.suncode.jar.code.universal.yolo.local

import android.graphics.RectF

data class DetectionResult(
    val boundingBox: RectF,
    val score: Float,
    val classIndex: Int
)
