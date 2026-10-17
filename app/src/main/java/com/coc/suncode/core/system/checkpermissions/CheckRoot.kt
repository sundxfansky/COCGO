package com.coc.suncode.core.system.checkpermissions

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.coc.suncode.MainActivity
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.ui.floatingwindows.UIWindowService
import com.coc.suncode.core.ui.localcomponents.LocalCustomButton
import com.coc.suncode.statehelper.AppMode
import com.coc.suncode.statehelper.AppStateManager
import com.topjohnwu.superuser.Shell

@Composable
fun CheckRootScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var status by remember { mutableStateOf(RootStatus.CHECKING) }
    var checkRequest by remember { mutableIntStateOf(0) }
    val retryRootCheck by rememberUpdatedState(newValue = {
        // Update status immediately to prevent duplicate checks from resume and button events.
        if (status == RootStatus.ROOT_DENIED || status == RootStatus.LOCAL_ERROR) {
            status = RootStatus.CHECKING
            checkRequest++
        }
    })

    LaunchedEffect(checkRequest) {
        status = PermissionManager.checkAndGrantPermissions(context) { newStatus ->
            status = newStatus
        }
    }

    // Recheck after the user returns from granting access in their root manager.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                retryRootCheck()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    when (status) {
        RootStatus.CHECKING -> {
            FullScreenMessage("正在检测Root环境...")
        }

        RootStatus.ROOT_DENIED -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                FullScreenMessage(
                    "暂未获取到 Root 权限。请在 Root 管理器中允许「太阳」，然后点击重新检测。"
                )
                Spacer(modifier = Modifier.height(16.dp))
                LocalCustomButton(
                    text = "重新检测 Root 权限",
                    onClick = retryRootCheck
                )
            }
        }

        RootStatus.PERMISSION_DENIED -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                FullScreenMessage(
                    "Root 授权成功，但仍需手动开启悬浮窗和通知权限。开启后请重启软件 。"
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }) {
                    Text("去设置中心开启")
                }
            }
        }

        RootStatus.INITIALIZING_LOCAL -> {
            FullScreenMessage("正在加载本地配置...")
        }

        RootStatus.LOCAL_ERROR -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                FullScreenMessage("本地配置初始化失败，请查看日志后重试。")
                LocalCustomButton(text = "重新加载配置", onClick = retryRootCheck)
            }
        }

        RootStatus.GRANTED -> {
            // Permissions and the internal backend are ready before starting overlay services.
            LaunchedEffect(Unit) {
                // Get and store default IME
                if (GlobalVars.defaultInputMethod == null) {
                    val result = Shell.cmd("settings get secure default_input_method").exec()
                    if (result.isSuccess && result.out.isNotEmpty()) {
                        GlobalVars.defaultInputMethod = result.out[0]
                    }
                }

                (context as? MainActivity)?.restartCloudSync()

                val serviceIntent = Intent(context, UIWindowService::class.java)
                context.startService(serviceIntent)
                (context as? MainActivity)?.requestMediaProjection()
            }
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                FullScreenMessage("权限检查通过，配置加载完成。\n\n若未能自动显示主界面，请手动点击按钮")
                Spacer(modifier = Modifier.height(16.dp))
                LocalCustomButton(
                    text = "显示主界面",
                    onClick = {
                        AppStateManager.setMode(AppMode.Main)
                        val serviceIntent = Intent(context, UIWindowService::class.java)
                        GlobalVars.isAutoRunEnabled = true
                        GlobalVars.autoRunTimer = 60
                        GlobalVars.updateWindowPosition = false
                        context.startService(serviceIntent)
                    }
                )
            }

        }
    }
}

@Composable
fun FullScreenMessage(message: String) {
    val colors = MaterialTheme.colorScheme

    // Keep the card content-sized so sibling action buttons remain visible.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = colors.surface,
            tonalElevation = 2.dp,
            shadowElevation = 4.dp,
            border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.12f))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Surface(
                    modifier = Modifier.size(56.dp),
                    shape = CircleShape,
                    color = colors.primaryContainer,
                    contentColor = colors.onPrimaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Text(
                    text = message,
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Medium,
                    fontSize = 18.sp,
                    lineHeight = 28.sp
                )
            }
        }
    }
}
