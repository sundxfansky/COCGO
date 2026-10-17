package com.coc.suncode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.coc.suncode.core.system.checkpermissions.CheckRootScreen
import com.coc.suncode.core.system.screencapture.ProjectionPermissionHelper
import com.coc.suncode.core.system.screencapture.ScreenCaptureManager
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.jar.code.universal.yolo.YoloDetector
import com.coc.suncode.jar.code.universal.ocr.PpOcrEngine
import com.coc.suncode.core.util.fileactions.LogHelper
import com.coc.suncode.core.data.cloud.CloudSyncManager
import com.topjohnwu.superuser.Shell

class MainActivity : ComponentActivity() {
    private lateinit var projectionPermissionHelper: ProjectionPermissionHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Install the in-app log sinks before model extraction so startup failures are visible.
        ShowMessage.init(applicationContext)
        LogHelper.initTimber(this)
        ShowMessage.record("MainActivity.onCreate 开始：package=$packageName")
        // Extract bundled ONNX models before any automation task requests detection.
        runCatching { YoloDetector.initialize(applicationContext) }
            .onSuccess { ShowMessage.record("YOLO 模型初始化完成") }
            .onFailure { ShowMessage.record("YOLO 模型初始化失败：${it.message}", throwable = it) }
            .getOrThrow()
        runCatching { PpOcrEngine.initialize(applicationContext) }
            .onSuccess { ShowMessage.record("OCR 模型初始化完成") }
            .onFailure { ShowMessage.record("OCR 模型初始化失败：${it.message}", throwable = it) }
            .getOrThrow()
        Shell.enableVerboseLogging = true
        projectionPermissionHelper = ProjectionPermissionHelper(this)
        System.loadLibrary("rust_logic")
        ScreenCaptureManager.init(this)
        ShowMessage.record("MainActivity.onCreate 完成：ScreenCaptureManager 已初始化")
        // Cloud sync is opt-in: provide CLOUD_CONFIG_URL and CLOUD_CONFIG_TOKEN in app preferences/build integration.
        setContent {
            CheckRootScreen()
        }

    }

    fun requestMediaProjection() {
        projectionPermissionHelper.requestMediaProjection()
    }


    override fun onResume() {
        super.onResume()
        ShowMessage.record("MainActivity.onResume：启动云同步检查")
        CloudSyncManager.ensureStarted(this)
    }

    override fun onDestroy() {
        ShowMessage.record("MainActivity.onDestroy")
        super.onDestroy()
    }

    fun restartCloudSync() { CloudSyncManager.restart(this) }
}
