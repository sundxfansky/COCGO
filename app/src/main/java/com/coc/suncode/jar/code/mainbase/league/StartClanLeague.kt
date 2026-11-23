package com.coc.suncode.jar.code.mainbase.league

import com.coc.suncode.jar.code.colorschema.MyColors
import com.coc.suncode.jar.code.universal.InGamesVars
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.jar.code.universal.colors.findMultiColorsUntil
import com.coc.suncode.jar.code.universal.enterMainScreen
import com.coc.suncode.jar.code.universal.smalltools.StorageKeys
import com.coc.suncode.jar.code.universal.smalltools.checkMemoryFile
import com.coc.suncode.jar.code.universal.smalltools.getBooleanConfigRuntime
import com.coc.suncode.jar.code.universal.smalltools.getConfigRuntime
import com.coc.suncode.jar.code.universal.smalltools.writeMemory
import com.coc.suncode.core.util.touchactions.TouchActions
import com.coc.suncode.jar.ui.schema.Schema

suspend fun startClanLeague(): Boolean {
    // 1. Check if START_CLAN_LEAGUE config is enabled
    if (!getBooleanConfigRuntime(Schema.MAIN_BASE_SETTINGS.START_LEAGUE_SETTINGS.key)) {
        return true
    }

    // 2. Check if within registration period
    if (!isInLeagueRegistrationPeriod()) {
        ShowMessage("当前不在联赛报名期，跳过开启联赛")
        return true
    }

    // 3. Cooldown check (once per day, 1440 minutes)
    val storageKey = StorageKeys.withAccountNumber(StorageKeys.START_CLAN_LEAGUE, InGamesVars.currentAccountNumber)
    if (!checkMemoryFile(storageKey, 1440)) {
        ShowMessage("今日已检测开启部落联赛，暂不重复执行")
        return true
    }

    if (!openLeaguePage()) return enterMainScreen()
    findMultiColorsUntil(schemas = listOf(MyColors.ClanWarEndButton), duration = 800)?.let {
        TouchActions.tap(it.x, it.y)
    }

    val start = findMultiColorsUntil(schemas = listOf(MyColors.StartClanLeagueButton), duration = 3000)
        ?: return enterMainScreen()
    TouchActions.tap(start.x, start.y)
    val confirmSchemas = listOf(MyColors.ConfirmClanLeagueRegistration)
    if (findMultiColorsUntil(schemas = confirmSchemas, duration = 2000) == null) return enterMainScreen()

    val size = getConfigRuntime(Schema.MAIN_BASE_SETTINGS.START_CLAN_LEAGUE_SIZE.key)
    val hasEleven = getBooleanConfigRuntime(Schema.MAIN_BASE_SETTINGS.START_CLAN_LEAGUE_NEW_SIZE.key)
    val x = when (size) {
        "2", "30" -> if (hasEleven) 865 else 504
        "0", "11" -> 179
        else -> if (hasEleven) 504 else 179
    }
    TouchActions.tap(x, 504)

    val confirm = findMultiColorsUntil(schemas = confirmSchemas, duration = 2000) ?: return enterMainScreen()
    TouchActions.tap(confirm.x, confirm.y, delayTime = 500)
    val submitted = findMultiColorsUntil(schemas = confirmSchemas, duration = 300) == null
    val returned = enterMainScreen()
    if (submitted && returned) {
        writeMemory(storageKey, (System.currentTimeMillis() / 60_000).toString())
        ShowMessage("已提交部落联赛报名")
    }
    return returned
}
