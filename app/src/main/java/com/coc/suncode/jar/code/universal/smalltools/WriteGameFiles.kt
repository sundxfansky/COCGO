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
 * The login plugin calls this host-backed helper when preparing the active account.
 *
 * @param packageName  Game package name (e.g. "com.supercell.clashofclans")
 * @param savePathName Archive folder suffix identifying the account save (e.g. "001")
 * @param folderName   Top-level archive folder on SD card (e.g. "sunGlobalGameSave")
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
            ShowMessage("存档写入失败，请检查存档及 Root 权限；已取消上号", isChecking = false)
            return false
        }
    }
    return true
}
