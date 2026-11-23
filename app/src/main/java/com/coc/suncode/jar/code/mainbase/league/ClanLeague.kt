package com.coc.suncode.jar.code.mainbase.league

import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.core.util.basic.delayWithMultiplier
import com.coc.suncode.core.util.touchactions.TouchActions
import com.coc.suncode.jar.code.colorschema.MyColors
import com.coc.suncode.jar.code.mainbase.others.mainBaseDeployTroops
import com.coc.suncode.jar.code.universal.InGamesVars
import com.coc.suncode.jar.code.universal.colors.findMultiColors
import com.coc.suncode.jar.code.universal.colors.findMultiColorsUntil
import com.coc.suncode.jar.code.universal.enterMainScreen
import com.coc.suncode.jar.code.universal.isInHomePage
import com.coc.suncode.jar.code.universal.recognizer.TextRecognizer
import com.coc.suncode.jar.code.universal.smalltools.StorageKeys
import com.coc.suncode.jar.code.universal.smalltools.checkMemoryFile
import com.coc.suncode.jar.code.universal.smalltools.getBooleanConfigRuntime
import com.coc.suncode.jar.code.universal.smalltools.killGame
import com.coc.suncode.jar.code.universal.smalltools.runGame
import com.coc.suncode.jar.code.universal.smalltools.writeMemory
import com.coc.suncode.jar.ui.schema.Schema

internal suspend fun openLeaguePage(): Boolean {
    if (!isInHomePage()) return false
    val entry = findMultiColorsUntil(
        schemas = listOf(MyColors.ClanLeagueEntryIndicator, MyColors.ClanLeagueEntryIndicator2, MyColors.ClanWarSwordIcon),
        duration = 2000, increment = 1
    ) ?: return false
    TouchActions.tap(entry.x, entry.y, delayTime = 500)
    return true
}

suspend fun playClanLeague(): Boolean =
    startClanLeague() && distributeClanLeagueRewards() && clanLeagueAttack() && leagueDonation() && checkLeagueShop()

private suspend fun clanLeagueAttack(): Boolean {
    if (!getBooleanConfigRuntime(Schema.MAIN_BASE_SETTINGS.PLAY_LEAGUE.key) || !isInClanLeaguePeriod()) return true
    val key = StorageKeys.withAccountNumber(StorageKeys.CLAN_LEAGUE_ATTACK, InGamesVars.currentAccountNumber)
    if (!checkMemoryFile(key, 720)) return true
    if (!openLeaguePage()) return enterMainScreen()

    val seasonPanel = listOf(MyColors.ClanLeagueSeasonInfo, MyColors.SmallBlueInfoIcon, MyColors.ClaimClanLeagueReward)
    repeat(3) {
        if (findMultiColors(MyColors.ClanLeagueGreenWarIcon, increment = 1) == null) {
            findMultiColorsUntil(schemas = seasonPanel, duration = 200, increment = 1)?.let {
                TouchActions.tap(it.x, it.y, delayTime = 300)
            }
        }
    }
    if (findMultiColorsUntil(schemas = listOf(MyColors.ClanLeagueGreenWarIcon), duration = 2500, increment = 1) == null) {
        return enterMainScreen()
    }

    ShowMessage("已进入部落联赛，寻找可进攻的对手")
    TouchActions.tap(1220, 490)
    TouchActions.tap(760, 60)
    val position = detectLeaguePosition()
    resetOpponentList()
    repeat((position ?: 1) - 1) { nextOpponent() }

    var attack = findMultiColorsUntil(schemas = listOf(MyColors.ClanWarAttack, MyColors.ClanWarAttack2), duration = 600)
    if (attack == null) {
        // The corresponding opponent can be unavailable. Search the roster from the top.
        resetOpponentList()
        for (index in 1..30) {
            attack = findMultiColorsUntil(schemas = listOf(MyColors.ClanWarAttack, MyColors.ClanWarAttack2), duration = 200)
            if (attack != null || index == 30) break
            nextOpponent()
        }
    }
    if (attack == null) return enterMainScreen()
    TouchActions.tap(attack.x, attack.y)
    TouchActions.tap(1080, 640)
    TouchActions.tap(780, 440)
    TouchActions.tap(1080, 640)
    TouchActions.tap(755, 470)
    findMultiColorsUntil(schemas = listOf(MyColors.EndBattle, MyColors.EndBattle2), duration = 30_000)
        ?: return enterMainScreen()
    ShowMessage("已进入部落联赛对战")
    mainBaseDeployTroops()
    writeMemory(key, (System.currentTimeMillis() / 60_000).toString())
    killGame()
    delayWithMultiplier(1000)
    runGame()
    return enterMainScreen()
}

private suspend fun detectLeaguePosition(): Int? {
    if (findMultiColorsUntil(schemas = listOf(MyColors.OurTeam), duration = 1000) == null) return null
    val result = try {
        var found: Int? = null
        for (attempt in 1..13) {
            val badge = findMultiColors(MyColors.MyPositionGreen, increment = 1)
            if (badge != null) {
                found = TextRecognizer.recognizeSingleLine(
                    (badge.x - 5).coerceAtLeast(0), badge.y + 5, (badge.x + 55).coerceAtMost(1280), (badge.y + 50).coerceAtMost(720)
                ).trim().toIntOrNull()?.takeIf { it in 1..30 }
                if (found != null) break
            }
            if (attempt < 13) TouchActions.swipe(320, 570, 320, 210)
        }
        found
    } finally {
        // The opponent map is behind the roster page.
        TouchActions.tap(1120, 55)
    }
    return result
}

private suspend fun resetOpponentList() {
    TouchActions.swipe(640, 300, 640, 710, delayTime = 500)
    TouchActions.tap(740, 450)
}

private suspend fun nextOpponent() = TouchActions.tap(950, 570, delayTime = 120)
