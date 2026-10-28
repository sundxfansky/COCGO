package com.coc.suncode.jar.ui.pages.single

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.qq.QqAuthorizationManager
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.jar.ui.components.CustomButton
import com.coc.suncode.jar.ui.components.SettingNumberStepper
import com.coc.suncode.jar.ui.components.SettingSection
import com.coc.suncode.jar.ui.schema.ConfigManager
import com.coc.suncode.jar.ui.schema.Schema
import com.coc.suncode.jar.ui.schema.Schema.GLOBAL_SETTINGS
import com.coc.suncode.jar.ui.schema.Schema.ACCOUNT_SETTINGS
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

@Composable
fun QqAuthorizationSettings() {
    SettingSection {
        Text("QQ 全局授权库", style = MaterialTheme.typography.titleSmall)
        QqAuthorizationSlotSelector(GLOBAL_SETTINGS.QQ_ACCOUNT_SLOT.key)
        val slot = GlobalVars.configStates[GLOBAL_SETTINGS.QQ_ACCOUNT_SLOT.key]?.value?.toIntOrNull()
        Text("授权按序号保存。账号 N 固定使用序号 N 的授权，例如账号 1 只读取授权 1。", style = MaterialTheme.typography.labelMedium)
        QqAuthorizationActions(slot)
    }
}

/** Persist only the selected reference, leaving unrelated unsaved settings untouched. */
@Composable
private fun QqAuthorizationSlotSelector(key: String) {
    val scope = rememberCoroutineScope()
    SettingNumberStepper(key = key, range = 1..10_000) { value ->
        if (value.toIntOrNull() in 1..10_000) {
            // Start the write immediately so switching tabs cannot drop a valid selection.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) {
                    try {
                        checkNotNull(GlobalVars.serverActions).writeToConfigFile(key, value)
                    } catch (error: Exception) {
                        ShowMessage.record("QQ 授权序号保存失败：${error.javaClass.simpleName}")
                        ShowMessage("QQ 授权序号保存失败，请重试或保存设置", isChecking = false)
                    }
                }
            }
        }
    }
}

@Composable
fun QqAccountAuthorizationSettings(index: Int) {
    // The slot is pinned to the account index, so it is shown read-only instead of as a stepper.
    val slot = ConfigManager.qqAuthorizationSlot(index)
    Text(
        "${Schema.getDisplayName(ACCOUNT_SETTINGS.QQ_AUTHORIZATION_SLOT.key)}：$slot（固定）",
        style = MaterialTheme.typography.labelMedium
    )
    Text("账号 $index 固定读取全局授权库中序号 $slot 的授权；新增或更新授权请到主页设置。", style = MaterialTheme.typography.labelMedium)
    QqAuthorizationActions(slot, library = false)
}

/** Both entry points resolve the same host-private authorization library by slot. */
@Composable
fun QqAuthorizationActions(slot: Int?, library: Boolean = true) {
    val context = LocalContext.current
    val status by QqAuthorizationManager.status.collectAsState()
    var summary by remember(slot) { mutableStateOf("") }
    val valid = slot != null && slot in 1..10_000
    LaunchedEffect(slot, status.revision) {
        // Refresh visible expiry information even when no new authorization operation occurs.
        while (true) {
            summary = if (valid) QqAuthorizationManager.summary(context, slot) else "请输入 1 至 10000 的授权序号"
            delay(60_000L)
        }
    }
    Text(summary, style = MaterialTheme.typography.labelMedium)
    FlowRow {
        if (library) CustomButton(
            text = "获取 QQ 授权", enable = valid && !status.busy,
            onClick = { QqAuthorizationManager.launch(context, slot!!, login = false) }
        )
        CustomButton(
            text = "使用此授权登录", enable = valid && !status.busy,
            onClick = { QqAuthorizationManager.launchSaved(context, slot!!) }
        )
    }
    if (library) Text("登录仅重置国服账号存档，保留资源文件；游戏协议会自动同意。自动轮换遇到授权缺失或过期时跳过该账号。", style = MaterialTheme.typography.labelMedium)
    // Show an operation's result at every account that references its authorization slot.
    if (status.accountSlot == slot && status.message.isNotEmpty()) Text(status.message, style = MaterialTheme.typography.labelMedium)
}
