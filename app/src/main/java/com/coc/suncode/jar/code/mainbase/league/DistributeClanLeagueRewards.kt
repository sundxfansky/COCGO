package com.coc.suncode.jar.code.mainbase.league

import android.os.SystemClock
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.core.util.basic.delayWithMultiplier
import com.coc.suncode.core.util.touchactions.TouchActions
import com.coc.suncode.jar.code.colorschema.MyColors
import com.coc.suncode.jar.code.universal.InGamesVars
import com.coc.suncode.jar.code.universal.colors.findMultiColors
import com.coc.suncode.jar.code.universal.colors.findMultiColorsUntil
import com.coc.suncode.jar.code.universal.enterMainScreen
import com.coc.suncode.jar.code.universal.smalltools.StorageKeys
import com.coc.suncode.jar.code.universal.smalltools.checkMemoryFile
import com.coc.suncode.jar.code.universal.smalltools.getBooleanConfigRuntime
import com.coc.suncode.jar.code.universal.smalltools.writeMemory
import com.coc.suncode.jar.ui.schema.Schema

suspend fun distributeClanLeagueRewards(): Boolean {
    if (!getBooleanConfigRuntime(Schema.MAIN_BASE_SETTINGS.DISTRIBUTE_CLAN_LEAGUE_REWARDS.key)) return true
    val key = StorageKeys.withAccountNumber(StorageKeys.DISTRIBUTE_CLAN_LEAGUE_REWARDS, InGamesVars.currentAccountNumber)
    if (!checkMemoryFile(key, 7 * 24 * 60)) return true
    if (!openLeaguePage()) return enterMainScreen()

    val distribute = findMultiColorsUntil(schemas = listOf(MyColors.DistributeClanLeagueRewards), duration = 2500)
        ?: return enterMainScreen()
    TouchActions.tap(distribute.x, distribute.y, delayTime = 400)
    val deadline = SystemClock.elapsedRealtime() + 30_000
    var selected = false
    while (SystemClock.elapsedRealtime() < deadline) {
        if (selected) {
            val confirm = findMultiColorsUntil(schemas = listOf(MyColors.MiddleGreenYes, MyColors.MiddleGreenConfirm), duration = 100)
            if (confirm != null) {
                TouchActions.tap(confirm.x, confirm.y, delayTime = 400)
                val returned = enterMainScreen()
                if (returned) {
                    writeMemory(key, (System.currentTimeMillis() / 60_000).toString())
                    ShowMessage("已提交部落联赛奖励分配")
                }
                return returned
            }
        }
        val player = findMultiColors(MyColors.SelectPlayerButton)
        if (player != null) {
            TouchActions.tap(player.x, player.y, delayTime = 300)
            selected = true
        } else {
            TouchActions.swipe(620, 560, 620, 300)
            delayWithMultiplier(300)
        }
    }
    return enterMainScreen()
}
