package com.coc.suncode.jar.code.clancapital

import com.coc.suncode.jar.code.colorschema.MyColors
import com.coc.suncode.jar.code.mainbase.others.zoomSmallMainBase
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.jar.code.universal.clickRightBottom
import com.coc.suncode.jar.code.universal.enterMainScreen
import com.coc.suncode.jar.code.universal.isInHomePage
import com.coc.suncode.jar.code.universal.colors.findMultiColors
import com.coc.suncode.jar.code.universal.colors.findMultiColorsUntil
import com.coc.suncode.core.util.touchactions.TouchActions

suspend fun playClanCapital(): Boolean {
    if (!collectCapitalCoins()) return false
    if (!contributeCapitalCoins()) return false
    if (!startRaid()) return false
    if (!performClanCapitalRaid()) return false
    return true
}


suspend fun enterClanCapital(): Boolean {
    if (findMultiColors(MyColors.CapitalCoin) != null || findMultiColors(MyColors.CapitalCoin2) != null) return true
    if (!isInHomePage()) return false
    zoomSmallMainBase()
    clickRightBottom(1)
    TouchActions.tap(495, 560, delayTime = 10)
    TouchActions.tap(440, 560, delayTime = 10)
    TouchActions.tap(445, 580, delayTime = 800)
    val nextRaid = findMultiColors(MyColors.NextRaid)
    if (nextRaid != null) {
        TouchActions.tap(nextRaid.x, nextRaid.y)
    }
    if (findMultiColorsUntil(schemas = listOf(MyColors.CapitalCoin, MyColors.CapitalCoin2), duration = 1500) != null) return true

    ShowMessage("进入都城失败")
    return false
}

/** Close the capital panels while their map controls are still visible. */
internal suspend fun returnFromCapital(): Boolean {
    for (attempt in 1..3) {
        if (isInHomePage()) return true
        findMultiColorsUntil(
            schemas = listOf(MyColors.CapitalCoin, MyColors.CapitalCoin2, MyColors.GemForCapital, MyColors.RaidInformation),
            duration = 150
        ) ?: break
        // The same bottom-left control returns from a district to the map, then to the village.
        TouchActions.tap(90, 620, delayTime = 500)
    }
    return enterMainScreen()
}
