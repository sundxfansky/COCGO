package com.coc.suncode.core.qq

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
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
            val status by QqAuthorizationManager.status.collectAsState()
            MaterialTheme {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(status.message)
                    Button(onClick = { finish() }) { Text("取消并返回") }
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
