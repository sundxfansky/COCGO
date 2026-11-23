package com.coc.suncode.jar.code.clancapital

import com.coc.suncode.jar.code.colorschema.MyColors
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.jar.code.universal.InGamesVars
import com.coc.suncode.jar.code.universal.colors.findMultiColorsUntil
import com.coc.suncode.jar.code.universal.smalltools.checkMemoryFile
import com.coc.suncode.jar.code.universal.smalltools.getBooleanConfigRuntime
import com.coc.suncode.jar.code.universal.smalltools.StorageKeys
import com.coc.suncode.jar.code.universal.smalltools.writeMemory
import com.coc.suncode.core.util.touchactions.TouchActions
import com.coc.suncode.jar.ui.schema.Schema

suspend fun startRaid(): Boolean {
    if (!getBooleanConfigRuntime(Schema.MAIN_BASE_SETTINGS.START_RAID.key)) return true
    if (!isWithinRaidWeekend()) {
        return true
    }
    // Guard: only start raid once per 3 days (4320 minutes)
    val storageKey = StorageKeys.withAccountNumber(StorageKeys.START_RAID, InGamesVars.currentAccountNumber)
    if (!checkMemoryFile(storageKey, 4320)) {
        ShowMessage("3天内已发起过突袭，跳过")
        return true
    }
    ShowMessage("准备开启突袭")
    if (!enterClanCapital()) return returnFromCapital()
    val startRaidButton = findMultiColorsUntil(schemas = listOf(MyColors.StartRaid), duration = 3000)
    if (startRaidButton != null) {
        TouchActions.tap(startRaidButton.x, startRaidButton.y)
        val confirm = findMultiColorsUntil(
            schemas = listOf(MyColors.MiddleGreenYes, MyColors.MiddleGreenConfirm), duration = 1200
        )
        if (confirm != null) TouchActions.tap(confirm.x, confirm.y)
        val started = findMultiColorsUntil(schemas = listOf(MyColors.RaidMap, MyColors.RaidInformation), duration = 2000)
        if (started != null) writeMemory(storageKey, (System.currentTimeMillis() / 60_000).toString())
    }
    return returnFromCapital()
}
