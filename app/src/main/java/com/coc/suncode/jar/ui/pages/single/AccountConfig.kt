package com.coc.suncode.jar.ui.pages.single

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.jar.ui.components.SettingDropdown
import com.coc.suncode.jar.ui.components.SettingInputRow
import com.coc.suncode.jar.ui.components.SettingSection
import com.coc.suncode.jar.ui.schema.Schema.ACCOUNT_SETTINGS

/** Account identity and login settings live only in the independent detail page. */
@Composable
fun AccountConfig(index: Int) {
    val version = GlobalVars.configStates["${ACCOUNT_SETTINGS.GAME_VERSION.key}$index"]?.value
    val startMethod = GlobalVars.configStates["${ACCOUNT_SETTINGS.START_METHOD.key}$index"]
    val isCn = version == "0"
    LaunchedEffect(index, isCn) {
        if (!isCn && startMethod?.value == "2") startMethod.value = "1"
    }
    SettingSection {
        SettingInputRow(key = "${ACCOUNT_SETTINGS.REMARK.key}$index")
        FlowRow {
            SettingDropdown(key = "${ACCOUNT_SETTINGS.GAME_VERSION.key}$index", options = listOf("国服", "国际服"))
            SettingDropdown(
                key = "${ACCOUNT_SETTINGS.START_METHOD.key}$index",
                options = if (isCn) listOf("游戏存档", "直接启动", "QQ 授权登录") else listOf("游戏存档", "直接启动")
            )
        }
        when (startMethod?.value) {
            "0" -> GameFiles(index)
            "2" -> if (isCn) QqAccountAuthorizationSettings(index)
        }
    }
}

@Composable
fun GameFiles(index: Int) {
    val isCn = GlobalVars.configStates["${ACCOUNT_SETTINGS.GAME_VERSION.key}$index"]?.value == "0"
    SettingInputRow(key = "${if (isCn) ACCOUNT_SETTINGS.CN_PATH.key else ACCOUNT_SETTINGS.GLOBAL_PATH.key}$index")
}
