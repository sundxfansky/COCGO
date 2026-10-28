package com.coc.suncode.jar.features.login

import com.coc.suncode.jar.code.universal.smalltools.AccountLoginResult
import com.coc.suncode.jar.code.universal.smalltools.getConfigOrStop
import com.coc.suncode.jar.code.universal.smalltools.writeGameFilesCore
import android.content.Context
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.qq.QqAuthorizationManager
import com.coc.suncode.core.ui.floatingwindows.BotRuntime
import com.coc.suncode.core.util.basic.ServerShell
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.jar.code.universal.GameVersion
import com.coc.suncode.jar.ui.schema.Schema.ACCOUNT_SETTINGS
import com.coc.suncode.jar.ui.schema.ConfigManager
import kotlinx.coroutines.CancellationException



/** Manual login uses the account's chosen method without enabling the automation loop. */
suspend fun loginConfiguredAccount(context: Context, index: Int): AccountLoginResult {
    if (QqAuthorizationManager.status.value.busy) return AccountLoginResult.FAILED
    GlobalVars.isAutoRunEnabled = false
    GlobalVars.isPlaying.value = false
    BotRuntime.stop()
    GlobalVars.isSwitchingAccount = true
    try {
        val version = when (getConfigOrStop("${ACCOUNT_SETTINGS.GAME_VERSION.key}$index")) {
            "0" -> GameVersion.CN
            "1" -> GameVersion.GLOBAL
            else -> { ShowMessage("请先为账号 $index 选择国服或国际服", false); return AccountLoginResult.FAILED }
        }
        when (getConfigOrStop("${ACCOUNT_SETTINGS.START_METHOD.key}$index")) {
            "2" -> {
                // Account N always uses QQ grant N.
                val slot = ConfigManager.qqAuthorizationSlot(index)
                if (version != GameVersion.CN || slot !in 1..10_000) {
                    ShowMessage("账号 $index 的 QQ 登录配置无效，请重新配置", false)
                    return AccountLoginResult.FAILED
                }
                return if (QqAuthorizationManager.launchSaved(context, slot)) AccountLoginResult.AUTHORIZING else AccountLoginResult.FAILED
            }
            "0" -> {
                val cn = version == GameVersion.CN
                val save = getConfigOrStop("${if (cn) ACCOUNT_SETTINGS.CN_PATH.key else ACCOUNT_SETTINGS.GLOBAL_PATH.key}$index")
                check(ServerShell.exec("am force-stop ${version.packageName}").isSuccess)
                if (!writeGameFilesCore(
                    version.packageName, save, if (cn) "sunCNGameSave" else "sunGlobalGameSave",
                    if (cn) listOf("shared_prefs", "databases") else listOf("shared_prefs"), killGame = false
                )) return AccountLoginResult.FAILED
            }
            "1" -> Unit
            else -> { ShowMessage("账号 $index 的登录方式无效", false); return AccountLoginResult.FAILED }
        }
        check(ServerShell.exec("am start -n ${version.launchComponent}").isSuccess)
        ShowMessage("已为账号 $index 发起登录", false)
        return AccountLoginResult.STARTED
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        ShowMessage.record("账号 $index 手动上号失败：${error.javaClass.simpleName}")
        ShowMessage("账号 $index 上号失败，请检查存档、游戏版本及 Root 权限", false)
        return AccountLoginResult.FAILED
    } finally {
        GlobalVars.isSwitchingAccount = false
    }
}
