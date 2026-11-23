package com.coc.suncode.jar.code.clancapital

import com.coc.suncode.jar.code.colorschema.MyColors
import com.coc.suncode.jar.code.mainbase.others.zoomSmallMainBase
import com.coc.suncode.jar.code.universal.InGamesVars
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.jar.code.universal.clickRightBottom
import com.coc.suncode.jar.code.universal.colors.findMultiColorsUntil
import com.coc.suncode.jar.code.universal.enterMainScreen
import com.coc.suncode.jar.code.universal.smalltools.StorageKeys
import com.coc.suncode.jar.code.universal.smalltools.checkMemoryFile
import com.coc.suncode.jar.code.universal.smalltools.getBooleanConfigRuntime
import com.coc.suncode.jar.code.universal.smalltools.writeMemory
import com.coc.suncode.core.util.touchactions.TouchActions
import com.coc.suncode.jar.ui.schema.Schema

suspend fun collectCapitalCoins(): Boolean {
    if (!getBooleanConfigRuntime(Schema.MAIN_BASE_SETTINGS.CLAIM_CAPITAL_GOLD.key)) return true
    val storageKey = StorageKeys.withAccountNumber(
        StorageKeys.COLLECT_CAPITAL_COINS, InGamesVars.currentAccountNumber
    )
    // Guard: only collect capital coins once per day (1440 minutes)
    if (!checkMemoryFile(storageKey, 1440)) {
        ShowMessage("该账号今日已领取都城币")
        return true
    }
    ShowMessage("正在领取都城币")
    zoomSmallMainBase()
    TouchActions.tap(560, 620)
    val collectCapitalCoins = findMultiColorsUntil(schemas = listOf(MyColors.CollectCapitalCoins), duration = 1000)
    if (collectCapitalCoins != null) {
        TouchActions.tap(collectCapitalCoins.x, collectCapitalCoins.y)
        // Record memory after successful collection
        writeMemory(storageKey, (System.currentTimeMillis() / 60_000).toString())
    }
    clickRightBottom(1)
    return enterMainScreen()
}
