package com.coc.suncode.jar.ui.pages.single

import com.coc.suncode.core.data.cloud.CloudConfigInbox
import com.coc.suncode.core.data.cloud.CloudSyncManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.coc.suncode.jar.ui.schema.ConfigManager
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.jar.ui.schema.Schema.GLOBAL_SETTINGS
import com.coc.suncode.jar.ui.components.CustomAlertDialog
import com.coc.suncode.jar.ui.components.CustomNotificationWindow
import com.coc.suncode.jar.ui.components.CustomButton
import com.coc.suncode.jar.ui.components.SettingSwitchIcon
import com.coc.suncode.jar.ui.components.SettingDropdown
import com.coc.suncode.jar.ui.components.SettingInputRow
import com.coc.suncode.jar.ui.components.SettingNumberStepper
import com.coc.suncode.jar.ui.components.SettingSection
import com.coc.suncode.core.ui.theme.AppColors
import com.coc.suncode.core.util.exit.AppExitHelper
import android.os.Environment
import androidx.compose.runtime.remember
import com.coc.suncode.core.util.fileactions.FileHelper
import com.coc.suncode.core.util.fileactions.LogHelper.showDebugInfo
import com.coc.suncode.statehelper.AppMode
import com.coc.suncode.statehelper.AppStateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.coc.suncode.core.util.basic.ServerShell
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.core.qq.QqAuthorizationManager
import com.coc.suncode.jar.code.universal.smalltools.AccountLoginResult
import com.coc.suncode.jar.code.universal.smalltools.loginConfiguredAccount

@Composable
fun HomeScreen(
    onSaveSuccess: () -> Unit = {}, onNavigateAccount: (Int) -> Unit = {}
) {

    // Ensure all keys are initialized if not already (safeguard)
    val actions = GlobalVars.serverActions
    if (actions != null && GlobalVars.configStates.isEmpty()) {
        ConfigManager.initializeAllConfigs(actions)
    }

    // Safety check for configuration state
    val accountCountState = GlobalVars.configStates[GLOBAL_SETTINGS.ACCOUNT_COUNT.key]
    if (accountCountState == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF2F3F5))
                .padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "正在初始化配置...", color = Color.Gray)
        }
        return
    }
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("主页设置", "账号设置")
    val lazyListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busyAccount by remember { mutableStateOf<Int?>(null) }
    val loginAccount: (Int) -> Unit = { index ->
        if (busyAccount == null) {
            busyAccount = index
            scope.launch {
                try {
                    if (loginConfiguredAccount(context, index) == AccountLoginResult.STARTED) {
                        GlobalVars.updateWindowPosition = true
                        AppStateManager.setMode(AppMode.Run)
                        onSaveSuccess()
                    }
                } finally { busyAccount = null }
            }
        }
    }
    // Save configs and explicitly set the playing state before closing the config window
    val saveAndSetPlaying: (Boolean) -> Unit = { play ->
        scope.launch {
            ConfigManager.saveAndRun {
                GlobalVars.isPlaying.value = play
                onSaveSuccess()
            }
        }
    }

    // Floating notification state
    var showNotification by remember { mutableStateOf(false) }
    var notificationMessage by remember { mutableStateOf("") }

    // Show a floating notification and auto-dismiss after 1200ms
    val showMsg: (String) -> Unit = { msg ->
        notificationMessage = msg
        showNotification = true
        scope.launch {
            delay(1200)
            showNotification = false
        }
    }

    val cleanMemory = {
        scope.launch {
            val sdPath = Environment.getExternalStorageDirectory().path
            val memoryPath = "$sdPath/sunFiles/memory.json"
            val success = FileHelper.deleteJson(memoryPath)
            showMsg(if (success) "记忆文件清除成功" else "清除失败，可能记忆文件不存在")
        }
    }

    // Exit Confirmation Dialog State
    var showExitConfirmation by rememberSaveable { mutableStateOf(false) }
    // Clean All Data Confirmation Dialog State
    var showCleanAllConfirmation by rememberSaveable { mutableStateOf(false) }
    var isClearingData by remember { mutableStateOf(false) }

    val cleanAllData = {
        showCleanAllConfirmation = true
    }

    // Auto-Run Timer Logic
    LaunchedEffect(GlobalVars.isAutoRunEnabled, GlobalVars.autoRunTimer) {
        if (GlobalVars.isAutoRunEnabled) {
            delay(1000L)
            GlobalVars.autoRunTimer--
            if (GlobalVars.autoRunTimer <= 0) {
                saveAndSetPlaying(true)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF2F3F5))
    ) {
        // 1. Top Tab bar
        PrimaryScrollableTabRow(
            selectedTabIndex = selectedTabIndex, edgePadding = 0.dp, minTabWidth = 0.dp, containerColor = AppColors.Azure
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    enabled = busyAccount == null, selected = selectedTabIndex == index, onClick = { selectedTabIndex = index }, modifier = Modifier
                        .wrapContentWidth() // Allows the tab to wrap its content
                        .widthIn(min = 0.dp), // BREAKS the default minimum width constraint
                    selectedContentColor = Color.White, unselectedContentColor = Color.White.copy(alpha = 0.75f), content = {
                        Text(
                            text = title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 5.dp, vertical = 5.dp)
                        )
                    })
            }
        }

        // 2. Middle content area (use weight to occupy remaining space)
        LazyColumn(
            state = lazyListState, modifier = Modifier
                .weight(1f)
                .padding(4.dp)
        ) {
            when (selectedTabIndex) {
                0 -> {
                    // Auto-Run UI in List
                    item {
                        SettingSection {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (GlobalVars.isAutoRunEnabled) "${GlobalVars.autoRunTimer}秒后自动运行" else "计时已停止",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.Red,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                                CustomButton(
                                    text = "修改任意配置停止计时", onClick = { GlobalVars.isAutoRunEnabled = false })
                            }
                        }
                        SettingSection {
                            FlowRow {
                                CustomButton(text = "截屏反馈问题", enable = busyAccount == null, onClick = {
                                    AppStateManager.setMode(AppMode.BugReport)
                                    onSaveSuccess()
                                })
                            }
                            FlowRow {
                                SettingInputRow(key = GLOBAL_SETTINGS.DELAY_MULTIPLIER.key)
                                SettingInputRow(key = GLOBAL_SETTINGS.ENTER_GAME_TIMER.key)
                                SettingSwitchIcon(key = GLOBAL_SETTINGS.RECORD_PROGRESS.key)


                                SettingDropdown(
                                    key = GLOBAL_SETTINGS.AUTO_UPDATE.key,
                                    options = listOf("关闭自动更新", "仅启动时更新", "实时自动更新")
                                )
                                SettingSwitchIcon(key = GLOBAL_SETTINGS.AUTO_START.key)
                                SettingDropdown(
                                    key = GLOBAL_SETTINGS.AFTER_KICK_OPTION.key,
                                    options = listOf("立刻重连", "切号并计时等待", "原地等待"),
                                    // Disable batch create when dropdown is not "立刻重连" (index 0)
                                    afterChange = { selectedIndex ->
                                        if (selectedIndex != 0) {
                                            GlobalVars.configStates[GLOBAL_SETTINGS.BATCH_CREATE_ACCOUNT.key]?.value = "0"
                                        }
                                    }
                                )
                                if (GlobalVars.configStates[GLOBAL_SETTINGS.AFTER_KICK_OPTION.key]?.value == "1") {
                                    SettingNumberStepper(GLOBAL_SETTINGS.AFTER_KICK_WAIT_MINUTES.key, 1..1440)
                                }
                                Column(Modifier.padding(top = 6.dp)) {
                                    SettingInputRow(key = GLOBAL_SETTINGS.DEVICE_REMARK.key)
                                }
                            }
                            FlowRow {
                                CustomButton(
                                    text = "清除账号记忆",
                                    onClick = { cleanMemory() },
                                    explain = "本辅助会记住账号信息，例如记住当前账号是否已完成突袭，是否已完成部落竞赛等等。如果换号后不清空记忆，那么本辅助就会保留先前账号错误的记忆，进而可能发生某些异常操作。"
                                )
                                CustomButton(
                                    text = "清除全部数据", enable = busyAccount == null, onClick = { cleanAllData() }, explain = "删除辅助设置、账号信息、全部 QQ 授权及授权工具缓存，用于保护用户隐私。"
                                )
                            }
                            Text(
                                text = "换机或设备到期前必须清空全部数据！部分云机在设备到期后不会清空用户数据，严重威胁隐私安全！",
                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    item {
                        QqAuthorizationSettings()
                    }
                    item {
                        CloudSettings()
                    }
                    item {
                        SettingSection {
                            BatchCreateAccount()
                        }
                    }
                }

                1 -> AccountSettings(onConfigure = onNavigateAccount, onLogin = loginAccount, busyAccount = busyAccount)
            }
        }

        // 3. Bottom fixed button area
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp)
        ) {
            CloudConfigInbox.pending?.let { pending ->
                Text("云端有新配置 v${pending.version}，当前修改尚未被覆盖")
                Row {
                    CustomButton(text = "应用云端配置", onClick = { scope.launch { CloudConfigInbox.applyRemote() } })
                    CustomButton(text = "保留本地修改", onClick = { CloudConfigInbox.keepLocal() })
                }
            }
            Row {
                CustomButton(
                    text = "保存并运行", enable = busyAccount == null, onClick = {
                        saveAndSetPlaying(true)
                    })
                CustomButton(
                    text = "保存并暂停", enable = busyAccount == null, onClick = {
                        saveAndSetPlaying(false)
                    })
                CustomButton(
                    text = "保存并退出", enable = busyAccount == null, onClick = {
                        showExitConfirmation = true
                    })
            }

        }

        if (showExitConfirmation) {
            CustomAlertDialog(onDismissRequest = { showExitConfirmation = false }, title = {
                Text(
                    text = "退出提示", style = MaterialTheme.typography.titleMedium
                )
            }, text = {
                Text(
                    text = "确认要退出吗？", style = MaterialTheme.typography.bodyMedium
                )
            }, confirmButton = {
                Row {
                    TextButton(onClick = { showExitConfirmation = false }) {
                        Text("取消")
                    }
                    TextButton(onClick = {
                        showExitConfirmation = false
                        scope.launch {
                            ConfigManager.saveAndRun {
                                AppExitHelper.exitApplication(context)
                            }
                        }
                    }) {
                        Text("确认")
                    }
                }
            })
        }

        // Confirmation dialog for clearing all data
        if (showCleanAllConfirmation) {
            CustomAlertDialog(onDismissRequest = { if (!isClearingData) showCleanAllConfirmation = false }, title = {
                Text(
                    text = "清除全部数据", style = MaterialTheme.typography.titleMedium
                )
            }, text = {
                Text(
                    text = "确认要删除所有数据吗？\n此操作不可撤销，将删除辅助设置、账号信息、全局 QQ 授权库及授权工具缓存。\n\n清除数据后，辅助将自动关闭，请手动重启辅助。", style = MaterialTheme.typography.bodyMedium
                )
            }, confirmButton = {
                Row {
                    TextButton(enabled = !isClearingData, onClick = { showCleanAllConfirmation = false }) {
                        Text("取消")
                    }
                    TextButton(enabled = !isClearingData, onClick = {
                        isClearingData = true
                        GlobalVars.isAutoRunEnabled = false
                        GlobalVars.isPlaying.value = false
                        // Stop writers before deleting data, then exit without saving the in-memory draft.
                        CloudSyncManager.stop()
                        scope.launch {
                            val sdPath = Environment.getExternalStorageDirectory().path
                            try {
                                withContext(Dispatchers.IO) {
                                    // QQ grants live outside sunFiles and must be cleared with ordinary app data.
                                    QqAuthorizationManager.clearSavedAuthorizations(context)
                                    val path = "'$sdPath/sunFiles'"
                                    check(ServerShell.exec("rm -rf -- $path && [ ! -e $path ]").isSuccess)
                                }
                            } catch (error: Exception) {
                                isClearingData = false
                                ShowMessage.record("全部数据清除未完成：${error.javaClass.simpleName}")
                                showMsg("数据清除未完成，请确认 QQ 授权操作已结束、Root 权限正常后重试")
                                return@launch
                            }
                            AppExitHelper.exitApplication(context)
                        }
                    }) {
                        Text(if (isClearingData) "正在清除..." else "确认")
                    }
                }
            })
        }

        // Floating notification overlay, auto-dismissed after 1200ms
        if (showNotification) {
            CustomNotificationWindow(
                message = notificationMessage, onDismissRequest = { })
        }
    }

}
