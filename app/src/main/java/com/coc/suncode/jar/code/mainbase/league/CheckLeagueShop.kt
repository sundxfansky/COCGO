package com.coc.suncode.jar.code.mainbase.league

import com.coc.suncode.core.util.basic.delayWithMultiplier
import com.coc.suncode.jar.code.colorschema.ColorSchema
import com.coc.suncode.jar.code.colorschema.MyColors
import com.coc.suncode.jar.code.universal.InGamesVars
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.jar.code.universal.colors.findMultiColors
import com.coc.suncode.jar.code.universal.colors.findMultiColorsUntil
import com.coc.suncode.jar.code.universal.enterMainScreen
import com.coc.suncode.jar.code.universal.smalltools.StorageKeys
import com.coc.suncode.core.util.touchactions.TouchActions
import com.coc.suncode.jar.code.universal.smalltools.checkMemoryFile
import com.coc.suncode.jar.code.universal.smalltools.getBooleanConfigRuntime
import com.coc.suncode.jar.code.universal.smalltools.writeMemory
import com.coc.suncode.jar.ui.schema.Schema

// Data-driven list of league shop items to purchase
private data class LeagueShopItem(
    val configKey: String,
    val colorSchemas: List<ColorSchema>,
)

private val leagueShopItems = listOf(
    LeagueShopItem(
        configKey = Schema.MAIN_BASE_SETTINGS.BUY_BUILDER_POTION_WITH_LEAGUE_MEDAL.key,
        colorSchemas = listOf(MyColors.LeagueShopBuilderPotion1, MyColors.LeagueShopBuilderPotion2),
    ),
    LeagueShopItem(
        configKey = Schema.MAIN_BASE_SETTINGS.BUY_RESEARCH_POTION_WITH_LEAGUE_MEDAL.key,
        colorSchemas = listOf(MyColors.LeagueShopResearchPotion1, MyColors.LeagueShopResearchPotion2),
    ),
)

suspend fun checkLeagueShop(): Boolean {
    val activeItems = leagueShopItems.filter { getBooleanConfigRuntime(it.configKey) }
    if (activeItems.isEmpty()) return true
    // Time guard: only check league shop once per 3 days (4320 minutes)
    val memoryKey = StorageKeys.withAccountNumber(StorageKeys.CHECK_LEAGUE_SHOP, InGamesVars.currentAccountNumber)
    if (!checkMemoryFile(memoryKey, 4320)) {
        ShowMessage("联赛商店检查冷却中，跳过")
        return true
    }

    val shopButton = findMultiColorsUntil(schemas = listOf(MyColors.ShopButton, MyColors.ShopButton2), duration = 500)
        ?: return enterMainScreen()
    TouchActions.tap(shopButton.x, shopButton.y)
    if (!enterLeagueShopPage()) return enterMainScreen()
    val remaining = activeItems.toMutableList()
    repeat(4) {
        val purchased = remaining.filter { buyLeagueShopItem(it) }
        remaining.removeAll(purchased.toSet())
        if (remaining.isNotEmpty()) {
            TouchActions.swipe(1000, 470, 80, 470)
            delayWithMultiplier(300)
        }
    }
    val returned = enterMainScreen()
    if (returned) writeMemory(memoryKey, (System.currentTimeMillis() / 60_000).toString())
    return returned
}

private suspend fun buyLeagueShopItem(item: LeagueShopItem): Boolean {
    val potion = findMultiColorsUntil(schemas = item.colorSchemas, duration = 200) ?: return false
    TouchActions.tap(potion.x, potion.y, delayTime = 350)
    val confirm = findMultiColorsUntil(schemas = listOf(MyColors.ClanLeagueConfirmBuy), duration = 500) ?: return false
    TouchActions.tap(confirm.x, confirm.y, delayTime = 400)
    return true
}

private suspend fun enterLeagueShopPage(): Boolean {
    val startTime = System.currentTimeMillis()
    while (System.currentTimeMillis() - startTime < 5000) {
        val oldVersion = findMultiColors(MyColors.OldVersion)
        if (oldVersion != null) {
            TouchActions.tap(oldVersion.x, oldVersion.y)
        }
        val leaguePageButton = findMultiColorsUntil(schemas = listOf(MyColors.LeaguePageButton, MyColors.LeaguePageButton2), duration = 50)
        if (leaguePageButton != null) {
            TouchActions.tap(leaguePageButton.x, leaguePageButton.y)
            TouchActions.swipe(1000, 470, 200, 470)
            delayWithMultiplier(300)
            return true
        }
        delayWithMultiplier(200)
    }
    return false
}
