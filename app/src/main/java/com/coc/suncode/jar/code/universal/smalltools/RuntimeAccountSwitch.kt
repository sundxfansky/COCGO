package com.coc.suncode.jar.code.universal.smalltools

import android.os.Environment
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.util.basic.ServerShell
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.core.util.fileactions.SavePath
import com.coc.suncode.interfaces.AccountSwitchState
import com.coc.suncode.jar.code.universal.GameVersion
import com.coc.suncode.jar.code.universal.InGamesVars
import com.coc.suncode.jar.ui.schema.Schema

/** Resolve exact neighbours, without wrapping or skipping a missing numbered save. */
object RuntimeAccountSwitch {
    private data class AccountSave(
        val number: Int,
        val version: GameVersion,
        val name: String
    ) {
        val folder get() = if (version == GameVersion.CN) "sunCNGameSave" else "sunGlobalGameSave"
        val subDirs get() = if (version == GameVersion.CN) listOf("shared_prefs", "databases") else listOf("shared_prefs")
        val source get() = "${Environment.getExternalStorageDirectory().path}/sunFiles/$folder/$name"
    }

    private fun config(key: String) = GlobalVars.configStates[key]?.value

    private fun accountRange(): IntRange {
        val batch = config(Schema.GLOBAL_SETTINGS.BATCH_CREATE_ACCOUNT.key) == "1"
        val first = if (batch) config(Schema.GLOBAL_SETTINGS.CREATE_START_ID.key)?.toIntOrNull() ?: 1 else 1
        val last = config(if (batch) Schema.GLOBAL_SETTINGS.CREATE_END_ID.key else Schema.GLOBAL_SETTINGS.ACCOUNT_COUNT.key)
            ?.toIntOrNull() ?: 0
        return first.coerceAtLeast(1)..last
    }

    private suspend fun currentAccount(): Int {
        // Before the first bot run, use the persisted position rather than the default account 1.
        if (InGamesVars.hasCurrentAccount) return InGamesVars.currentAccountNumber
        val range = accountRange()
        return readMemory(StorageKeys.ACCOUNT_NUMBER).toIntOrNull()?.takeIf { it in range } ?: range.first
    }

    private fun accountSave(number: Int): AccountSave? {
        if (number !in accountRange()) return null
        val batch = config(Schema.GLOBAL_SETTINGS.BATCH_CREATE_ACCOUNT.key) == "1"
        val version = if (batch) GameVersion.GLOBAL else GameVersion.entries.firstOrNull {
            it.id == config("${Schema.ACCOUNT_SETTINGS.GAME_VERSION.key}$number")?.toIntOrNull()
        } ?: return null
        if (version == GameVersion.PRIVATE) return null
        val key = if (version == GameVersion.CN) Schema.ACCOUNT_SETTINGS.CN_PATH.key else Schema.ACCOUNT_SETTINGS.GLOBAL_PATH.key
        val name = if (batch) number.toString() else config("$key$number") ?: return null
        return AccountSave(number, version, name).takeIf { SavePath.isValid(name) }
    }

    private suspend fun saveExists(save: AccountSave) = ServerShell.exec("[ -d \"${save.source}\" ]").isSuccess

    suspend fun state(): AccountSwitchState {
        if (!GlobalVars.isConfigLoaded || GlobalVars.serverActions == null) return AccountSwitchState()
        val current = currentAccount()
        val previous = accountSave(current - 1)?.takeIf { saveExists(it) }?.number
        val next = accountSave(current + 1)?.takeIf { saveExists(it) }?.number
        return AccountSwitchState(previous, next)
    }

    suspend fun switchTo(accountNumber: Int): Boolean {
        val current = currentAccount()
        val save = accountSave(accountNumber)
        // Recheck after the bot has stopped: the toolbar snapshot may already be stale.
        if (accountNumber.toLong() !in listOf(current.toLong() - 1, current.toLong() + 1) ||
            save == null || !saveExists(save)
        ) {
            ShowMessage("目标账号存档不存在或账号已变化，请重新选择", isChecking = false)
            return false
        }
        ShowMessage("正在切换到账号 $accountNumber", isChecking = false)
        if (!ServerShell.exec("am force-stop ${save.version.packageName}").isSuccess) {
            ShowMessage("关闭游戏失败，辅助保持暂停", isChecking = false)
            return false
        }
        if (!writeGameFilesCore(save.version.packageName, save.name, save.folder, save.subDirs, killGame = false)) {
            return false
        }
        if (!ServerShell.exec("am start -n ${save.version.launchComponent}").isSuccess) {
            ShowMessage("游戏启动失败，辅助保持暂停", isChecking = false)
            return false
        }
        InGamesVars.currentAccountNumber = save.number
        InGamesVars.currentGameVersion = save.version
        if (!writeMemory(StorageKeys.ACCOUNT_NUMBER, save.number.toString())) {
            ShowMessage("已切号，但账号记忆保存失败，辅助保持暂停", isChecking = false)
            return false
        }
        ShowMessage("已切换到账号 $accountNumber，辅助保持暂停", isChecking = false)
        return true
    }
}
