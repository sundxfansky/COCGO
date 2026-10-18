package com.coc.suncode.jar.features.login

import com.coc.suncode.jar.code.universal.smalltools.RuntimeAccountSwitch
import android.content.Context
import com.coc.suncode.interfaces.FeaturePlugin
import com.coc.suncode.interfaces.FeatureResult
import com.coc.suncode.jar.code.universal.smalltools.AccountLoginResult
import com.coc.suncode.jar.code.universal.enterMainScreen

/** Feature entry point; shared state is resolved through the parent core loader. */
class Entry : FeaturePlugin {
    override suspend fun execute(context: Context, action: String, account: Int): FeatureResult {
        if (action == "manual") return when (loginConfiguredAccount(context, account)) {
            AccountLoginResult.STARTED -> FeatureResult.SUCCESS
            AccountLoginResult.AUTHORIZING -> FeatureResult.AUTHORIZING
            AccountLoginResult.FAILED -> FeatureResult.FAILED
        }
        val success = when (action) {
            "switch" -> RuntimeAccountSwitch.switchTo(account)
            "prepare" -> writeGameFiles()
            "enter" -> enterMainScreen(true)
            else -> error("Unsupported action: $action")
        }
        return if (success) FeatureResult.SUCCESS else FeatureResult.FAILED
    }
}
