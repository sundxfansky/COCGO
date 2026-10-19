package com.coc.suncode.jar.code.universal.yolo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.jar.code.universal.yolo.local.LocalYoloEngine
import kotlin.coroutines.cancellation.CancellationException

/** Compatibility facade replacing the former com.coc.sunyolo HTTP service. */
object YoloDetector {
    @Volatile
    private var initialized = false
    private val initializationLock = Any()

    /** Initializes model extraction. Safe to call repeatedly. */
    fun initialize(context: Context) {
        val appContext = context.applicationContext
        synchronized(initializationLock) {
            if (initialized) return
            LocalYoloEngine.initialize(appContext)
            initialized = true
            ShowMessage.record(
                "YOLO facade initialized: context=${appContext.packageName}, loader=${javaClass.classLoader}",
                Log.INFO,
                "YoloDetector"
            )
        }
    }

    /**
     * The plugin is loaded by an isolated class loader, so host MainActivity's
     * initialization does not initialize this copy of the facade. Resolve the
     * host context lazily through the host-owned diagnostic bridge instead.
     */
    private fun ensureInitialized(): Boolean {
        if (initialized) return true
        val context = ShowMessage.getContext()
        if (context == null) {
            ShowMessage.record(
                "YOLO initialization deferred: host context is unavailable, loader=${javaClass.classLoader}",
                Log.WARN,
                "YoloDetector"
            )
            return false
        }
        initialize(context)
        return initialized
    }

    suspend fun loadWeights(modelType: String? = null): Boolean {
        if (!ensureInitialized()) {
            ShowMessage("本地AI引擎尚未初始化，宿主上下文不可用", isChecking = false)
            return false
        }
        return try {
            LocalYoloEngine.loadWeights(modelType ?: "remove-obstacle")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ShowMessage.record("YOLO model load failed: type=${modelType ?: "remove-obstacle"}", Log.ERROR, "YoloDetector", e)
            ShowMessage("本地模型加载失败: ${e.message}", isChecking = false)
            false
        }
    }

    suspend fun detect(bitmap: Bitmap, clearWeightsAfter: Boolean = true, threshold: Float = 0.3f, distanceThreshold: Double = 5.0): List<DetectionResult> {
        if (!ensureInitialized()) {
            ShowMessage("本地AI引擎尚未初始化，宿主上下文不可用", isChecking = false)
            return emptyList()
        }
        return try {
            val localResults = LocalYoloEngine.detect(bitmap, clearWeightsAfter = clearWeightsAfter, threshold = threshold)
            LocalYoloEngine.filterCloseDetections(localResults, distanceThreshold).map {
                DetectionResult(RectF(it.boundingBox), it.score, it.classIndex)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ShowMessage.record("YOLO inference failed: loadedType=${LocalYoloEngine.getModelType()}", Log.ERROR, "YoloDetector", e)
            ShowMessage("本地检测异常: ${e.message}", isChecking = false)
            emptyList()
        }
    }

    suspend fun clearWeights() {
        if (ensureInitialized()) {
            LocalYoloEngine.clearWeights()
        }
    }
}
