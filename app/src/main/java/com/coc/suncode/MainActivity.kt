package com.coc.suncode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.coc.suncode.core.system.checkpermissions.CheckRootScreen
import com.coc.suncode.core.system.screencapture.ProjectionPermissionHelper
import com.coc.suncode.core.system.screencapture.ScreenCaptureManager
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.core.util.fileactions.LogHelper
import com.coc.suncode.core.data.cloud.CloudSyncManager
import com.topjohnwu.superuser.Shell

class MainActivity : ComponentActivity() {
    private lateinit var projectionPermissionHelper: ProjectionPermissionHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shell.enableVerboseLogging = true
        projectionPermissionHelper = ProjectionPermissionHelper(this)
        ShowMessage.init(this)
        System.loadLibrary("rust_logic")
        ScreenCaptureManager.init(this)
        LogHelper.initTimber(this)
        LogHelper.showDebugInfo("MainActivity Start!")
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
        CloudSyncManager.ensureStarted(this)
    }

    fun restartCloudSync() { CloudSyncManager.restart(this) }
}
