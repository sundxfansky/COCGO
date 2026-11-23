package com.coc.suncode.jar.code.clancapital

import com.coc.suncode.core.util.basic.delayWithMultiplier
import com.coc.suncode.jar.code.colorschema.MyColors
import com.coc.suncode.jar.code.universal.InGamesVars
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.jar.code.universal.clickRightBottom
import com.coc.suncode.jar.code.universal.colors.findMultiColors
import com.coc.suncode.jar.code.universal.colors.findMultiColorsUntil
import com.coc.suncode.jar.code.universal.smalltools.StorageKeys
import com.coc.suncode.jar.code.universal.smalltools.checkMemoryFile
import com.coc.suncode.jar.code.universal.smalltools.getBooleanConfigRuntime
import com.coc.suncode.jar.code.universal.smalltools.writeMemory
import com.coc.suncode.core.util.touchactions.TouchActions
import com.coc.suncode.jar.ui.schema.Schema

suspend fun contributeCapitalCoins(): Boolean {
    if (!getBooleanConfigRuntime(Schema.MAIN_BASE_SETTINGS.DONATE_CAPITAL_GOLD.key)) return true

    val storageKey = StorageKeys.withAccountNumber(
        StorageKeys.CONTRIBUTE_CAPITAL_COINS, InGamesVars.currentAccountNumber
    )
    // Guard: only contribute once per 2 days (2880 minutes)
    if (!checkMemoryFile(storageKey, 2880)) {
        ShowMessage("2天内已捐赠过都城币，跳过")
        return true
    }

    ShowMessage("准备捐都城币")
    if (!enterClanCapital()) {
        ShowMessage("进入都城失败，返回主页面")
        return returnFromCapital()
    }
    delayWithMultiplier(1000)
    if (findMultiColors(MyColors.SufficientCapitalCoin) == null) {
        ShowMessage("都城币太少，等币多一点后再捐")
        writeMemory(storageKey, (System.currentTimeMillis() / 60_000).toString())
        return returnFromCapital()
    }
    // Try contributing at the capital overview building list
    TouchActions.tap(570, 50)
    if (tryContributeAtBuildingList()) {
        writeMemory(storageKey, (System.currentTimeMillis() / 60_000).toString())
        return returnFromCapital()
    } else {
        clickRightBottom(1)
        delayWithMultiplier(100)
    }

    // Iterate all districts
    for (district in CAPITAL_DISTRICTS) {
        ShowMessage("检查区域: ${district.name}")

        // Enter the district
        if (!enterDistrict(district)) continue

        if (findMultiColorsUntil(schemas = listOf(MyColors.GemForCapital), duration = 8000) == null) {
            ShowMessage("进入子城失败")
            return returnFromCapital()
        }

        // Try contributing at the district building list

        TouchActions.tap(537, 75)
        if (tryContributeAtBuildingList()) {
            writeMemory(storageKey, (System.currentTimeMillis() / 60_000).toString())
            return returnFromCapital()
        }

        // No coin found in this district, dismiss
        TouchActions.tap(90, 620, delayTime = 1000)
        if (findMultiColorsUntil(schemas = listOf(MyColors.CapitalCoin, MyColors.CapitalCoin2), duration = 1500) == null) {
            return returnFromCapital()
        }
    }

    return returnFromCapital()
}

/**
 * Attempt to contribute capital coins at the current building-list view.
 * Taps the purple coin icon if visible, then looks for an upgrade button.
 * If an upgrade is found, taps it and the contribute button.
 *
 * @return true if a contribution was made (caller should exit), false otherwise.
 */
private suspend fun tryContributeAtBuildingList(): Boolean {
    val miniPurpleCoin = findMultiColorsUntil(schemas = listOf(MyColors.MiniPurpleCoinIcon, MyColors.MiniPurpleCoinIcon2), duration = 1000) ?: return false
    TouchActions.tap(miniPurpleCoin.x, miniPurpleCoin.y)
    // Check for upgrade button
    val upgradeButtonPoint = findMultiColorsUntil(schemas = listOf(MyColors.UpgradeHammer, MyColors.DoubleHammer), duration = 3000
    )
    if (upgradeButtonPoint != null) {
        TouchActions.tap(upgradeButtonPoint.x, upgradeButtonPoint.y, delayTime = 1000)
        TouchActions.tap(950, 640) // contribute
        return true
    }
    clickRightBottom(1)
    return false
}

/**
 * Tap into a specific capital district.
 * Goblin Mines (哥布矿井) uses a special tap coordinate due to its position at the bottom of the map.
 */
private suspend fun enterDistrict(district: CapitalDistrict): Boolean {
    ShowMessage("正在进入区域: ${district.name}")
    TouchActions.tap(district.entryX, district.entryY)
    delayWithMultiplier(800)

    // Check if district is locked using colour schema
    val lockedPoint = findMultiColors(MyColors.CapitalDistrictLocked)
    if (lockedPoint != null) {
        ShowMessage("区域 ${district.name} 未解锁，跳过")
        return false
    }

    ShowMessage("成功进入区域: ${district.name}")
    delayWithMultiplier(1000)
    return true
}
