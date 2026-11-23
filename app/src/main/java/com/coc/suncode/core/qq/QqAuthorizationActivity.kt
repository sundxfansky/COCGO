package com.coc.suncode.core.qq

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.coc.suncode.core.ui.web.SettingsRequestException
import com.coc.suncode.core.ui.web.SettingsWebPanel
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Manifest-owned bridge keeps result routing out of the dynamically loaded module. */
class QqAuthorizationActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[QqAuthorizationViewModel::class.java] }
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        model.result.complete(if (result.resultCode == Activity.RESULT_OK) result.data?.getStringExtra("key_response") else null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Status stays on the bundled page while Android handles the external activity result.
            SettingsWebPanel(surface = "authorization", modifier = Modifier.fillMaxSize()) { method, _ ->
                when (method) {
                    "getAuthorizationActivityStatus" -> JsonObject().apply {
                        addProperty("message", QqAuthorizationManager.status.value.message)
                    }
                    "closeAuthorizationActivity" -> {
                        finish()
                        JsonObject()
                    }
                    else -> throw SettingsRequestException(404, "不支持的授权操作")
                }
            }
        }
        lifecycleScope.launch {
            model.launchTool.collect { launch ->
                if (launch) {
                    model.launchTool.value = false
                    model.externalLaunched = true
                    try {
                        launcher.launch(Intent().setComponent(ComponentName(QqToolBackend.PACKAGE, QqToolBackend.ACTIVITY)))
                        model.markToolOpened()
                    } catch (_: Exception) {
                        model.result.complete(null)
                    }
                }
            }
        }
        lifecycleScope.launch { model.finished.collect { if (it) finish() } }
        model.start(
            applicationContext,
            intent.getStringExtra("request_id").orEmpty(),
            intent.getIntExtra("slot", 0),
            intent.getBooleanExtra("login", false),
            savedInstanceState?.getBoolean("external_launched") == true,
            restoring = savedInstanceState != null,
            resetStarted = savedInstanceState?.getBoolean("reset_started") == true,
            allowCapture = intent.getBooleanExtra("allow_capture", true)
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("external_launched", model.externalLaunched && !model.result.isCompleted)
        outState.putBoolean("reset_started", model.resetStarted)
        super.onSaveInstanceState(outState)
    }
}

/** Retain the pending result across configuration changes without launching the tool twice. */
class QqAuthorizationViewModel : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val result = CompletableDeferred<String?>()
    val launchTool = MutableStateFlow(false)
    val finished = MutableStateFlow(false)
    var externalLaunched = false
    private var started = false
    private var request: QqAuthorizationManager.Request? = null
    val resetStarted: Boolean get() = request?.resetStarted == true

    fun markToolOpened() {
        request?.toolOpened?.complete(Unit)
    }

    fun start(
        context: Context, id: String, slot: Int, login: Boolean, restoreLaunched: Boolean,
        restoring: Boolean, resetStarted: Boolean, allowCapture: Boolean
    ) {
        if (started) return
        started = true
        externalLaunched = restoreLaunched
        // Keep unattended rotation unattended after Activity or process recreation.
        val request = QqAuthorizationManager.restore(id, slot, login, allowRestore = restoring, allowCapture = allowCapture)
        if (request == null) {
            finished.value = true
            return
        }
        this.request = request
        // A retained ViewModel already returned above; this branch therefore represents process recreation.
        if (resetStarted) {
            QqAuthorizationManager.interruptRestoredLogin(request)
            finished.value = true
            return
        }
        scope.launch {
            try {
                QqAuthorizationManager.execute(context, request, restoreLaunched) {
                    if (!externalLaunched) launchTool.value = true
                    result.await()
                }
            } finally {
                finished.value = true
            }
        }
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }
}
