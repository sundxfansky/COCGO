package com.coc.suncode.jar.code.mainbase.daily

import com.coc.suncode.jar.code.universal.isInHomePage
import com.coc.suncode.jar.code.colorschema.MyColors
import com.coc.suncode.jar.code.universal.InGamesVars
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.jar.code.universal.colors.findMultiColorsUntil
import com.coc.suncode.jar.code.universal.enterMainScreen
import com.coc.suncode.jar.code.universal.smalltools.StorageKeys
import com.coc.suncode.jar.code.universal.smalltools.checkMemoryFile
import com.coc.suncode.jar.code.universal.smalltools.getBooleanConfigRuntime
import com.coc.suncode.jar.code.universal.smalltools.writeMemory
import com.coc.suncode.core.util.touchactions.TouchActions
import com.coc.suncode.jar.ui.schema.Schema

suspend fun collectVillagerCommission(): Boolean {
    // Skip when the commission claim switch is disabled in settings.
    if (!getBooleanConfigRuntime(Schema.MAIN_BASE_SETTINGS.CLAIM_VILLAGER_COMMISSIONS.key)) return true

    val storageKey = StorageKeys.withAccountNumber(StorageKeys.CLAIM_VILLAGER_COMMISSIONS, InGamesVars.currentAccountNumber)

    // Time guard: only run the commission check once per day (1440 minutes) per account.
    if (!checkMemoryFile(storageKey, 1440)) {
        return true
    }

    ShowMessage("开始领取村民委托")

    // Open the commission page.
    TouchActions.tap(1215, 360, delayTime = 500)
    if (isInHomePage()) return true

    // Keep scanning for up to 5 seconds and tap each completion button as it appears,
    // so multiple ready commissions are claimed one after another.
    val startTime = System.currentTimeMillis()
    while (System.currentTimeMillis() - startTime < 5000) {
        findMultiColorsUntil(schemas = listOf(MyColors.FinishCommission), duration = 100, increment = 1)?.let {
            TouchActions.tap(it.x, it.y)
        }
    }

    // Open the chest to collect the accumulated commission rewards.
    TouchActions.tap(555, 565)

    // Record the attempt only after the page has closed successfully.
    val returned = enterMainScreen()
    if (returned) writeMemory(storageKey, (System.currentTimeMillis() / 60_000).toString())
    return returned
}
