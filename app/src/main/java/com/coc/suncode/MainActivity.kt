package com.coc.suncode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.coc.suncode.core.system.checkpermissions.CheckRootScreen
import com.coc.suncode.core.system.screencapture.ProjectionPermissionHelper
import com.coc.suncode.core.system.screencapture.ScreenCaptureManager
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.core.util.fileactions.LogHelper
import com.coc.suncode.core.data.cloud.CloudConfigSync
import com.coc.suncode.core.data.database.GlobalVars
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

class MainActivity : ComponentActivity() {
    private lateinit var projectionPermissionHelper: ProjectionPermissionHelper
    private val cloudScope = MainScope()
    private var cloudSync: CloudConfigSync? = null

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


    override fun onDestroy() {
        cloudSync?.stop()
        cloudScope.cancel()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // The website/token are optional; a missing token keeps the app fully offline.
        val token = getSharedPreferences("cloud", MODE_PRIVATE).getString("token", "") ?: ""
        val actions = GlobalVars.serverActions
        if (cloudSync == null && actions != null && BuildConfig.BASE_URL.isNotBlank() && token.isNotBlank()) {
            cloudSync = CloudConfigSync(BuildConfig.BASE_URL.trimEnd('/') + "/api/v1", token, actions)
            cloudSync?.start(cloudScope)
        }
    }
}
