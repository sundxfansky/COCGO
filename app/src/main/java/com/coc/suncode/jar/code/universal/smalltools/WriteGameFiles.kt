package com.coc.suncode.jar.code.universal.smalltools

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
import com.coc.suncode.core.util.basic.ServerShell
import com.coc.suncode.core.qq.QqAuthorizationManager

// Helper function: quickly get config value, trigger logAndRestart if empty
fun getConfigOrStop(key: String): String {
    return GlobalVars.configStates[key]?.value ?: logAndRestart("Failed to get config: $key")
}

// Returns the save path name (archive folder suffix) for the current account based on its game version
fun getGameFilePath(): String {
    val index = InGamesVars.currentAccountNumber
    if (getConfigOrStop(Schema.GLOBAL_SETTINGS.BATCH_CREATE_ACCOUNT.key) == "1")
        return index.toString()
    return when (InGamesVars.currentGameVersion) {
        GameVersion.CN -> getConfigOrStop("${ACCOUNT_SETTINGS.CN_PATH.key}$index")
        GameVersion.GLOBAL -> getConfigOrStop("${ACCOUNT_SETTINGS.GLOBAL_PATH.key}$index")
        // PRIVATE and any future versions do not have a configured save path
        else -> ""
    }
}

/**
 * Core helper: copies each subdirectory from the SD card archive into the game's data directory,
 * clears the destination first, then sets permissions to 777.
 * This is called by both [writeGameFiles] and SwitchAccount UI.
 *
 * @param packageName  Game package name (e.g. "com.supercell.clashofclans")
 * @param savePathName Archive folder suffix identifying the account save (e.g. "001")
 * @param folderName   Top-level archive folder on SD card (e.g. "zkqGlobalGameSave")
 * @param subDirs      Subdirectories to copy (e.g. ["shared_prefs", "databases"])
 */
@SuppressLint("SdCardPath")
suspend fun writeGameFilesCore(
    packageName: String, savePathName: String, folderName: String, subDirs: List<String>, killGame: Boolean = true
): Boolean {
    // Validate the archive component before passing any command to the root server.
    if (!SavePath.isValid(savePathName)) { ShowMessage("存档名无效"); return false }
    val sdPath = Environment.getExternalStorageDirectory().path
    val sourceDir = "$sdPath/sunFiles/$folderName/$savePathName"

    // Validate every source before clearing any destination, including empty save directories.
    if (subDirs.isEmpty() || subDirs.any { !ServerShell.exec("[ -d \"$sourceDir/$it\" ]").isSuccess }) {
        ShowMessage("存档文件不存在：$sourceDir\n即将跳过当前账号")
        return false
    }
    ShowMessage("写入存档文件中，路径：$sourceDir")
    if (killGame) killGame()
    subDirs.forEach { subDir ->
        val destPath = "/data/data/$packageName/$subDir"
        // Stop on the first root error so manual login never starts with a failed copy.
        val copied = ServerShell.exec(
            "rm -rf \"$destPath\" && mkdir -p \"$destPath\" && " +
                "cp -R \"$sourceDir/$subDir/.\" \"$destPath/\" && chmod -R 777 \"$destPath\""
        ).isSuccess
        if (!copied) {
            ShowMessage("存档写入失败，请检查存档及 Root 权限；已取消上号")
            return false
        }
    }
    return true
}

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
        // Multiple automation accounts may deliberately share the same global authorization slot.
        val account = InGamesVars.currentAccountNumber
        val slot = getConfigOrStop("${ACCOUNT_SETTINGS.QQ_AUTHORIZATION_SLOT.key}$account").toIntOrNull()
        if (slot == null || slot !in 1..10_000) {
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
        GameVersion.CN -> "zkqCNGameSave" to listOf("shared_prefs", "databases")
        GameVersion.GLOBAL -> "zkqGlobalGameSave" to listOf("shared_prefs")
        // nothing to write
        else -> return true
    }

    return writeGameFilesCore(version.packageName, savePathName, folderName, subDirs)
}
