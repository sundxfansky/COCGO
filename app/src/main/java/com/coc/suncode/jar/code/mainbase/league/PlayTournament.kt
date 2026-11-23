package com.coc.suncode.jar.code.mainbase.league

import android.os.SystemClock
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
import com.coc.suncode.jar.code.universal.smalltools.StorageKeys
import com.coc.suncode.jar.code.universal.smalltools.checkMemoryFile
import com.coc.suncode.jar.code.universal.smalltools.checkReconnections
import com.coc.suncode.jar.code.universal.smalltools.getBooleanConfigRuntime
import com.coc.suncode.jar.code.universal.smalltools.killGame
import com.coc.suncode.jar.code.universal.smalltools.runGame
import com.coc.suncode.jar.code.universal.smalltools.writeMemory
import com.coc.suncode.jar.ui.schema.Schema

suspend fun playTournament(): Boolean {
    if (!getBooleanConfigRuntime(Schema.MAIN_BASE_SETTINGS.PLAY_TOURNAMENT.key)) return true
    val key = StorageKeys.withAccountNumber(StorageKeys.PLAY_TOURNAMENT, InGamesVars.currentAccountNumber)
    if (!checkMemoryFile(key, 1440)) return true
    if (!isInHomePage()) return enterMainScreen()

    TouchActions.tap(80, 630, delayTime = 600)
    val deadline = SystemClock.elapsedRealtime() + 120_000
    val terminal = listOf(
        MyColors.GreenSword, MyColors.CompletedTournamentSword, MyColors.PreparingTournament,
        MyColors.PreparingTournament2, MyColors.TournamentNotAvailable
    )
    val rankedActions = listOf(MyColors.ConfirmTournamentAttack, MyColors.IncreaseRank, MyColors.PlayTournament)
    var rankedSelected = false
    while (SystemClock.elapsedRealtime() < deadline) {
        if (!checkReconnections()) return false
        if (findMultiColorsUntil(schemas = terminal, duration = 0, increment = 1) != null) {
            writeMemory(key, (System.currentTimeMillis() / 60_000).toString())
            break
        }
        if (findMultiColors(MyColors.WaitForTournament, increment = 1) != null) break

        if (rankedSelected &&
            findMultiColorsUntil(schemas = listOf(MyColors.EndBattle, MyColors.EndBattle2), duration = 0) != null
        ) {
            ShowMessage("已进入排位对战")
            mainBaseDeployTroops()
            killGame()
            delayWithMultiplier(1000)
            runGame()
            break
        }
        val registration = findMultiColors(MyColors.JoinTournament, increment = 1)
        if (registration != null) {
            TouchActions.tap(registration.x, registration.y, delayTime = 350)
            TouchActions.tap(865, 570, delayTime = 350)
            continue
        }

        val ranked = findMultiColorsUntil(schemas = rankedActions, duration = 0, increment = 1)
        if (ranked != null) {
            TouchActions.tap(ranked.x, ranked.y, delayTime = 500)
            rankedSelected = true
        } else if (rankedSelected) {
            // The shared attack button is only valid after selecting ranked play.
            findMultiColors(MyColors.AttackButton, increment = 1)?.let {
                TouchActions.tap(it.x, it.y, delayTime = 500)
            }
        }
        delayWithMultiplier(250)
    }
    return enterMainScreen()
}
