@file:Suppress("unused")

package com.coc.suncode.jar.ui

import android.content.Context
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.util.basic.RunShell
import com.coc.suncode.interfaces.FeaturePluginClient
import com.coc.suncode.interfaces.FeatureResult
import com.coc.suncode.jar.features.FeatureRuntime
import com.coc.suncode.interfaces.MainCode
import com.coc.suncode.interfaces.AccountSwitchState
import com.coc.suncode.interfaces.RuntimeAccountSwitcher
import com.coc.suncode.jar.code.universal.smalltools.RuntimeAccountSwitch
import com.coc.suncode.core.data.websocket.ServerActions
import com.coc.suncode.jar.code.runMainScript
import com.coc.suncode.jar.ui.components.CustomButton
import com.coc.suncode.jar.ui.pages.mainbase.MainBaseUpgradePriority
import com.coc.suncode.jar.ui.pages.builderbase.BuilderBaseUpgradePriority
import com.coc.suncode.jar.ui.pages.single.HomeScreen
import com.coc.suncode.jar.ui.pages.single.AccountDetailScreen
import com.coc.suncode.jar.ui.pages.single.BugReport
import com.coc.suncode.jar.ui.schema.ConfigManager
import com.coc.suncode.statehelper.AppMode
import com.coc.suncode.statehelper.AppStateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

class EnterMainCode : MainCode, RuntimeAccountSwitcher, FeaturePluginClient {
    override fun initializeFeatures(context: Context, bundledPlugins: Map<String, String>) =
        FeatureRuntime.initialize(context, bundledPlugins)

    override fun reloadFeatures() = FeatureRuntime.reload()

    // Keep account configuration and archive resolution inside the active dynamic module.
    override suspend fun accountSwitchState(): AccountSwitchState = RuntimeAccountSwitch.state()

    override suspend fun switchToAccount(accountNumber: Int): Boolean = FeatureRuntime.execute("login", "switch", accountNumber) == FeatureResult.SUCCESS

    override fun refreshConfigs(actions: ServerActions) {
        ConfigManager.initializeAllConfigs(actions)
        GlobalVars.isConfigLoaded = true
    }

    @Composable
    override fun ShowMainUI(context: Context, onClose: () -> Unit) {
        // Initialize states from Schema
        // A prepared plugin can render its home page on the first composition.
        var isConfigInitialized by remember { mutableStateOf(GlobalVars.isConfigLoaded) }

        LaunchedEffect(Unit) {
            if (GlobalVars.isConfigLoaded) {
                isConfigInitialized = true
                return@LaunchedEffect
            }

            // Wait for serverActions to become available (may be null if UIWindowService
            // was restarted by Android before CheckRoot finishes setting up serverActions)
            val actions = GlobalVars.serverActions ?: run {
                snapshotFlow { GlobalVars.serverActions }.first { it != null }!!
            }

            // Wait for configs to load
            snapshotFlow { actions.isLoading }.first { !it }
            ConfigManager.initializeAllConfigs(actions)
            isConfigInitialized = true
            GlobalVars.isConfigLoaded = true
        }
        if (!isConfigInitialized) {
            ConfigLoadingScreen()
            return
        }

        // Route to the appropriate page based on current mode
        when (AppStateManager.currentMode) {
            AppMode.BugReport -> {
                BugReport(onClose = onClose)
                return
            }
            // Older plugins may retain the retired mode; route it to the ordinary settings page.
            AppMode.Main, AppMode.SwitchAccount -> {
                val navController = rememberNavController()
                // Any touch on any settings page (tabs, scrolling, blank areas) stops the auto-run countdown.
                // Observed in the Initial pass without consuming, so child controls still receive the event.
                Box(Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        GlobalVars.isAutoRunEnabled = false
                    }
                }) {
                    NavHost(navController = navController, startDestination = "home") {
                        composable("home") {
                            HomeScreen(
                                onSaveSuccess = onClose,
                                onNavigateAccount = { index -> navController.navigate("account/$index") }
                            )
                        }
                        composable("account/{index}") { entry ->
                            AccountDetailScreen(
                                initialIndex = entry.arguments?.getString("index")?.toIntOrNull() ?: 1,
                                onBack = { navController.popBackStack() },
                                onNavigatePriority = { index -> navController.navigate("priority/$index") },
                                onNavigateBuilderBasePriority = { index -> navController.navigate("builder_base_priority/$index") }
                            )
                        }
                        composable("priority/{index}") { backStackEntry ->
                            val index = backStackEntry.arguments?.getString("index")?.toIntOrNull() ?: 1
                            MainBaseUpgradePriority(
                                index = index,
                                onSaveSuccess = {
                                    navController.popBackStack()
                                }
                            )
                        }
                        composable("builder_base_priority/{index}") { backStackEntry ->
                            val index = backStackEntry.arguments?.getString("index")?.toIntOrNull() ?: 1
                            BuilderBaseUpgradePriority(
                                index = index,
                                onSaveSuccess = {
                                    navController.popBackStack()
                                }
                            )
                        }
                    }
                }
            }
            else -> {}
        }
    }

    override suspend fun runBot() {
        runMainScript()
    }
}

@Composable
private fun ConfigLoadingScreen() {
    var countdown by remember { mutableIntStateOf(20) }

    LaunchedEffect(Unit) {
        while (countdown > 0) {
            delay(1000L)
            countdown--
        }
        // Skip waitForPlay check — force-stop should execute immediately
        RunShell.runNoOutput("am force-stop com.coc.suncode >>/dev/null 2>&1", isCheckIsPlaying = false)
    }

    val scope = rememberCoroutineScope()

    Column {
        Text("正在初始化配置文件...\n若长时间卡在此界面，将在 $countdown 秒后自动重启。")
        CustomButton(
            text = "点击此处手动关闭辅助",
            onClick = {
                // Launch on IO dispatcher to avoid blocking the main thread (ANR)
                scope.launch(Dispatchers.IO) {
                    RunShell.runNoOutput("am force-stop com.coc.suncode >>/dev/null 2>&1", isCheckIsPlaying = false)
                }
            }
        )
    }
}
