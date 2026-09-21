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
import com.coc.suncode.jar.ui.schema.ConfigManager
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
        startCloudSyncIfReady()
    }

    /** Starts cloud synchronization after the root WebSocket client is ready. */
    fun restartCloudSync() {
        cloudSync?.stop()
        cloudSync = null
        startCloudSyncIfReady()
    }

    private fun startCloudSyncIfReady() {
        // The website/token are optional; a missing token keeps the app fully offline.
        val token = getSharedPreferences("cloud", MODE_PRIVATE).getString("token", "") ?: ""
        val actions = GlobalVars.serverActions
        val defaultEndpoint = BuildConfig.BASE_URL
            .takeIf { it.isNotBlank() }
            ?.trimEnd('/')
            ?.plus("/api/v1")
            .orEmpty()
        val endpoint = getSharedPreferences("cloud", MODE_PRIVATE)
            .getString("base_url", defaultEndpoint)?.trimEnd('/') ?: defaultEndpoint
        if (cloudSync == null && actions != null && endpoint.isNotBlank() && token.isNotBlank()) {
            cloudSync = CloudConfigSync(endpoint, token, actions) {
                cloudScope.launch(Dispatchers.Main.immediate) {
                    // Rebuild Compose states so remote values are visible immediately.
                    ConfigManager.initializeAllConfigs(actions)
                    GlobalVars.isConfigLoaded = true
                }
            }
            cloudSync?.start(cloudScope)
        }
    }
}
