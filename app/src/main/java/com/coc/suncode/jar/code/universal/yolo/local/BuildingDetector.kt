package com.coc.suncode.jar.code.universal.yolo.local

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.coc.suncode.core.util.basic.ShowMessage

object LocalYoloEngine {
    private const val TAG = "LocalYoloEngine"

    private val modelLock = Any()

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var currentModelType: String? = null
    @Volatile
    private var onnxRuntime: OnnxRuntime? = null

    fun initialize(context: Context) {
        val applicationContext = context.applicationContext
        synchronized(modelLock) {
            if (appContext === applicationContext) return
            appContext = applicationContext
            // Model files are extracted lazily for the requested model. This avoids
            // multiple isolated plugin class loaders overwriting shared cache files.
            ModelAssetManager.startExtraction(applicationContext)
            ShowMessage.record(
                "YOLO engine initialized: package=${applicationContext.packageName}, loader=${javaClass.classLoader}",
                Log.INFO,
                TAG
            )
        }
    }

    private fun contextOrHost(): Context? {
        appContext?.let { return it }
        val hostContext = ShowMessage.getContext()?.applicationContext ?: return null
        synchronized(modelLock) {
            if (appContext == null) {
                appContext = hostContext
                ShowMessage.record(
                    "YOLO engine lazily adopted host context: package=${hostContext.packageName}, loader=${javaClass.classLoader}",
                    Log.INFO,
                    TAG
                )
            }
            return appContext
        }
    }

    @Synchronized
    fun isModelLoaded(): Boolean = onnxRuntime?.isLoaded == true

    @Synchronized
    fun getModelType(): String? = currentModelType

    /**
     * Loads model weights. Skips if the same model type is already loaded.
     * Throws on failure so the caller can report the error.
     */
    @Synchronized
    fun loadWeights(modelType: String) {
        if (isModelLoaded() && currentModelType == modelType) {
            ShowMessage.record("YOLO model already loaded: type=$modelType", Log.DEBUG, TAG)
            return
        }

        val context = contextOrHost()
            ?: throw IllegalStateException("LocalYoloEngine must be initialized and host context must be available")

        val assetName = when (modelType) {
            "building-detect" -> "my_building_detector.onnx"
            "capital-building-detect" -> "capital_building_detector.onnx"
            "clan-game" -> "clan_game_detector.onnx"
            "clan-war-numbers" -> "clan_war_number_detector.onnx"
            "main-base-battle" -> "main_base_battle.onnx"
            "numbers" -> "numbers_detector.onnx"
            "remove-obstacle" -> "obstacles_detector.onnx"
            "walls-detect" -> "walls_detector.onnx"
            else -> throw IllegalArgumentException(
                "Unknown modelType: \"$modelType\". Valid types: walls-detect, numbers, building-detect, capital-building-detect, remove-obstacle, clan-war-numbers, clan-game, main-base-battle"
            )
        }

        clearWeights()

        // Resolve only the requested model. The resolver validates the source
        // version and publishes a complete file atomically before loading it.
        val modelFile = ModelAssetManager.resolveModelFile(context, assetName)

        val runtime = OnnxRuntime()
        try {
            runtime.load(modelFile)
            onnxRuntime = runtime
            currentModelType = modelType
            ShowMessage.record("Model loaded: type=$modelType, file=${modelFile.name}, input=${runtime.inputWidth}x${runtime.inputHeight}", Log.INFO, TAG)
        } catch (error: Exception) {
            runtime.close()
            ShowMessage.record("YOLO model initialization failed: type=$modelType, file=${modelFile.absolutePath}", Log.ERROR, TAG, error)
            throw error
        }
    }

    @Synchronized
    fun clearWeights() {
        onnxRuntime?.close()
        onnxRuntime = null
        currentModelType = null
    }

    /**
     * Runs inference on the given bitmap. Model must already be loaded via loadWeights().
     */
    @Synchronized
    fun detect(
        bitmap: Bitmap,
        clearWeightsAfter: Boolean = false,
        threshold: Float = 0.3f
    ): List<DetectionResult> {
        val runtime = onnxRuntime ?: run {
            ShowMessage.record("YOLO detect requested without a loaded model", Log.WARN, TAG)
            return emptyList()
        }

        var scaledBitmap: Bitmap? = null
        try {
            val (resized, scale, offset) = ImagePreprocessor.resizeWithPadding(
                bitmap, runtime.inputWidth, runtime.inputHeight
            )
            scaledBitmap = resized
            val (offX, offY) = offset

            return runtime.detect(scaledBitmap, scale, offX, offY, threshold)
        } finally {
            if (scaledBitmap != null && scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }
            if (clearWeightsAfter) {
                clearWeights()
            }
        }
    }

    /**
     * Filters out detections whose centers are closer than distanceThreshold,
     * keeping the higher-confidence one.
     */
    fun filterCloseDetections(
        detections: List<DetectionResult>,
        distanceThreshold: Double = 5.0
    ): List<DetectionResult> {
        val filtered = mutableListOf<DetectionResult>()

        for (detection in detections) {
            var isTooClose = false
            val iterator = filtered.listIterator()

            while (iterator.hasNext()) {
                val existing = iterator.next()
                val dx = detection.boundingBox.centerX() - existing.boundingBox.centerX()
                val dy = detection.boundingBox.centerY() - existing.boundingBox.centerY()
                val distance = kotlin.math.sqrt((dx * dx + dy * dy).toDouble())

                if (distance < distanceThreshold) {
                    isTooClose = true
                    if (detection.score > existing.score) {
                        iterator.remove()
                        iterator.add(detection)
                    }
                    break
                }
            }

            if (!isTooClose) {
                filtered.add(detection)
            }
        }
        return filtered
    }
}
