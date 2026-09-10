package com.coc.suncode.jar.code.universal.smalltools

import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.util.fileactions.LogHelper.logAndRestart
import com.coc.suncode.jar.code.universal.InGamesVars
import com.coc.suncode.jar.ui.schema.Schema

fun getConfigRuntime(configName: String): String {
    // When batch create account is enabled, all accounts share config group 1
    val configNumber = if (getConfigOrStop(Schema.GLOBAL_SETTINGS.BATCH_CREATE_ACCOUNT.key) == "1") {
        "1"
    } else {
        GlobalVars.configStates["account_config${InGamesVars.currentAccountNumber}"]?.value ?: logAndRestart(
            "Can not get the config number for account ${InGamesVars.currentAccountNumber}"
        )
    }
    // Fall back to the schema default when the runtime state has not been initialized yet.
    val result = GlobalVars.configStates["${configName}_c$configNumber"]?.value
        ?: Schema.getDefaultValue("${configName}_c$configNumber")
    return result
}

fun getBooleanConfigRuntime(configName: String): Boolean {
    return getConfigRuntime(configName) == "1"
}

fun getStaticConfig(key: String): String {
    return GlobalVars.configStates[key]?.value ?: Schema.getDefaultValue(key)
}