package com.coc.suncode.jar.features.login

import android.annotation.SuppressLint
import android.os.Environment
import com.coc.suncode.core.util.fileactions.SavePath
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.core.util.fileactions.LogHelper.logAndRestart
import com.coc.suncode.jar.code.universal.GameVersion
import com.coc.suncode.jar.code.universal.InGamesVars
import com.coc.suncode.jar.ui.schema.Schema
import com.coc.suncode.jar.ui.schema.Schema.ACCOUNT_SETTINGS
import com.coc.suncode.jar.ui.schema.ConfigManager
import com.coc.suncode.core.util.basic.ServerShell
import com.coc.suncode.core.qq.QqAuthorizationManager

import com.coc.suncode.jar.code.universal.smalltools.getConfigOrStop
import com.coc.suncode.jar.code.universal.smalltools.getGameFilePath
import com.coc.suncode.jar.code.universal.smalltools.killGame
import com.coc.suncode.jar.code.universal.smalltools.writeGameFilesCore

/**
 * Writes the game save files for the current account (as tracked by [InGamesVars]) back
 * to the game's data directory, then sets permissions to 777.
 */
suspend fun writeGameFiles(): Boolean {
    val startMethod = getConfigOrStop("${ACCOUNT_SETTINGS.START_METHOD.key}${InGamesVars.currentAccountNumber}").toIntOrNull() ?: logAndRestart("${ACCOUNT_SETTINGS.START_METHOD.displayName} 必须是数字，请检查配置")
    // QQ login uses the host bridge and awaits the login request before automation can touch the game.
    if (startMethod == 2) {
        if (InGamesVars.currentGameVersion != GameVersion.CN) {
            ShowMessage("QQ 授权登录仅支持国服")
            return false
        }
        val context = ShowMessage.getContext() ?: return false
        // Account N always uses QQ grant N, regardless of any stale value in the config file.
        val account = InGamesVars.currentAccountNumber
        val slot = ConfigManager.qqAuthorizationSlot(account)
        if (slot !in 1..10_000) {
            ShowMessage("账号 $account 的 QQ 授权序号无效，本轮跳过")
            return false
        }
        return QqAuthorizationManager.loginAccount(context, slot)
    }
    if (startMethod != 0) return true
    killGame()
    val savePathName = getGameFilePath()
    val version = InGamesVars.currentGameVersion

    val (folderName, subDirs) = when (version) {
        GameVersion.CN -> "sunCNGameSave" to listOf("shared_prefs", "databases")
        GameVersion.GLOBAL -> "sunGlobalGameSave" to listOf("shared_prefs")
        // nothing to write
        else -> return true
    }

    return writeGameFilesCore(version.packageName, savePathName, folderName, subDirs)
}
