@file:Suppress("unused")

package com.coc.suncode.jar.ui

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.data.websocket.ServerActions
import com.coc.suncode.core.ui.web.LocalSettingsController
import com.coc.suncode.core.ui.web.SettingsWebPanel
import com.coc.suncode.core.ui.web.WebSettingsProvider
import com.coc.suncode.core.ui.web.WebSettingsSession
import com.coc.suncode.interfaces.AccountSwitchState
import com.coc.suncode.interfaces.FeaturePluginClient
import com.coc.suncode.interfaces.FeatureResult
import com.coc.suncode.interfaces.MainCode
import com.coc.suncode.interfaces.RuntimeAccountSwitcher
import com.coc.suncode.jar.code.runMainScript
import com.coc.suncode.jar.code.universal.smalltools.RuntimeAccountSwitch
import com.coc.suncode.jar.features.FeatureRuntime
import com.coc.suncode.jar.ui.pages.single.createWebSettingsController as createSettingsController
import com.coc.suncode.jar.ui.schema.ConfigManager

class EnterMainCode : MainCode, RuntimeAccountSwitcher, FeaturePluginClient, WebSettingsProvider {
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

    override fun createWebSettingsController(context: Context): LocalSettingsController =
        createSettingsController(context)

    /** Retain the original ABI for callers that still render the dynamic plugin entry directly. */
    @Composable
    override fun ShowMainUI(context: Context, onClose: () -> Unit) {
        val actions = GlobalVars.serverActions ?: return
        val currentOnClose = rememberUpdatedState(onClose)
        val session = remember(actions) {
            WebSettingsSession(context, createWebSettingsController(context)) { currentOnClose.value() }
        }
        LaunchedEffect(session) { session.runCountdown() }
        SettingsWebPanel(
            modifier = Modifier.fillMaxSize(),
            onInteraction = { GlobalVars.isAutoRunEnabled = false },
            request = session::request
        )
    }

    override suspend fun runBot() = runMainScript()
}
