package com.coc.suncode.jar.code.mainbase.league

import com.coc.suncode.core.util.basic.RunShell
import com.coc.suncode.core.util.basic.delayWithMultiplier
import com.coc.suncode.jar.code.colorschema.ColorSchema
import com.coc.suncode.jar.code.colorschema.MyColors
import com.coc.suncode.jar.code.colorschema.colorpackage.mainbase.MainBaseClanWarColors
import com.coc.suncode.jar.code.universal.InGamesVars
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.jar.code.universal.colors.findMultiColors
import com.coc.suncode.jar.code.universal.colors.findMultiColorsUntil
import com.coc.suncode.jar.code.universal.enterMainScreen
import com.coc.suncode.core.util.touchactions.TouchActions
import com.coc.suncode.jar.code.universal.smalltools.StorageKeys
import com.coc.suncode.jar.code.universal.smalltools.checkMemoryFile
import com.coc.suncode.jar.code.universal.smalltools.getBooleanConfigRuntime
import com.coc.suncode.jar.code.universal.smalltools.getConfigRuntime
import com.coc.suncode.jar.code.universal.smalltools.writeMemory
import com.coc.suncode.jar.ui.schema.Schema
import com.coc.suncode.core.system.screencapture.ScreenCaptureManager

private const val LEAGUE_DONATION_INTERVAL_MINUTES = 720  // 12 hours

suspend fun leagueDonation(): Boolean {
    if (!getBooleanConfigRuntime(Schema.MAIN_BASE_SETTINGS.PLAY_LEAGUE_DONATION.key)) {
        return true
    }

    if (!isInClanLeaguePeriod()) {
        return true
    }

    val storageKey = StorageKeys.withAccountNumber(
        StorageKeys.LEAGUE_DONATION,
        InGamesVars.currentAccountNumber
    )
    if (!checkMemoryFile(storageKey, LEAGUE_DONATION_INTERVAL_MINUTES)) {
        ShowMessage("12小时内已执行过联赛捐兵，跳过本次")
        return true
    }

    ShowMessage("开始联赛捐兵")

    if (!openLeaguePage()) {
        return enterMainScreen()
    }

    if (!enterLeaguePrepInterface()) {
        return enterMainScreen()
    }

    TouchActions.swipe(640, 300, 640, 710, delayTime = 500)
    delayWithMultiplier(500)

    TouchActions.tap(534, 477, delayTime = 500)

    // Confirm the details page before tapping any member or donation controls.
    if (findMultiColorsUntil(schemas = listOf(MyColors.LeagueFirstMemberIndicator), duration = 3500) == null) {
        return enterMainScreen()
    }
    repeat(30) {
        if (!shouldSkipMember()) donateToLeagueMember()
        if (findMultiColors(MyColors.LeagueGrayArrow) != null) {
            writeMemory(storageKey, (System.currentTimeMillis() / 60_000).toString())
            return enterMainScreen()
        }
        TouchActions.tap(950, 570, delayTime = 200)
    }
    return enterMainScreen()
}

private suspend fun enterLeaguePrepInterface(): Boolean {
    val preparePhase = findMultiColorsUntil(schemas = listOf(MyColors.LeaguePreparePhase, MyColors.LeaguePreparePhase2), duration = 5000)
    if (preparePhase != null) {
        ShowMessage("检测到联赛准备阶段，点击进入")
        TouchActions.tap(preparePhase.x, preparePhase.y)

        val whiteCloud = findMultiColorsUntil(schemas = listOf(MyColors.LeagueWhiteCloud), duration = 5000)
        if (whiteCloud != null) {
            ShowMessage("已成功进入联赛准备界面")
            return true
        }
    }
    return false
}

private suspend fun shouldSkipMember(): Boolean {
    // Color-based progress bar check — skip when ≥98% full.
    val percentIndicator = findMultiColors(MyColors.LeaguePercentIndicator)
    if (percentIndicator != null) {
        ShowMessage("检测到百分比指示器(进度≥98%)，跳过该成员")
        return true
    }
    return false
}

private suspend fun donateToLeagueMember() {
    val troopList = MainBaseClanWarColors.leagueDonatableTroopList

    // Read all 3 troop configs: (troopIndex, count)
    val configs = listOf(
        Schema.MAIN_BASE_SETTINGS.LEAGUE_DONATION_TROOP_1.key to Schema.MAIN_BASE_SETTINGS.LEAGUE_DONATION_COUNT_1.key,
        Schema.MAIN_BASE_SETTINGS.LEAGUE_DONATION_TROOP_2.key to Schema.MAIN_BASE_SETTINGS.LEAGUE_DONATION_COUNT_2.key,
        Schema.MAIN_BASE_SETTINGS.LEAGUE_DONATION_TROOP_3.key to Schema.MAIN_BASE_SETTINGS.LEAGUE_DONATION_COUNT_3.key,
    )


    val activeDonations = configs.mapNotNull { (troopKey, countKey) ->
        val configTroopIndex = getConfigRuntime(troopKey).toIntOrNull() ?: 0
        val count = (getConfigRuntime(countKey).toIntOrNull() ?: 0).coerceIn(0, 60)
        ShowMessage.record("联赛捐兵配置: configTroopIndex=$configTroopIndex, count=$count")

        if (configTroopIndex <= 0) {
            ShowMessage.record("联赛捐兵: 选择了'无', 跳过")
            return@mapNotNull null
        }
        if (count <= 0) {
            ShowMessage.record("联赛捐兵: count<=0, 跳过")
            return@mapNotNull null
        }

        // UI index 1+ -> troopList index 0+
        val troopIndex = configTroopIndex - 1
        if (troopIndex !in troopList.indices) {
            ShowMessage.record("联赛捐兵: troopIndex=$troopIndex 越界, 跳过")
            return@mapNotNull null
        }

        val (troopName, schema) = troopList[troopIndex]
        Triple(troopName, schema, count)
    }

    if (activeDonations.isEmpty()) {
        ShowMessage("未配置任何有效捐兵")
        return
    }

    val request = findMultiColors(MyColors.LeagueReinforceRequest) ?: return
    TouchActions.tap(request.x, request.y, delayTime = 300)
    var anyDonated = false
    var remainingDonations = activeDonations

    // Swipe left first to ensure we start from the leftmost position
    ShowMessage.record("联赛捐兵: 先向左滑动")
    TouchActions.swipe(998, 290, 120, 290)
    delayWithMultiplier(400)

    // Try up to 2 screens: left screen + right swipe
    repeat(2) { screenAttempt ->
        if (remainingDonations.isEmpty()) return@repeat

        // Capture one frame for all troop icons on this page.
        val screenBuffer = ScreenCaptureManager.capture(asBitmap = false) as? ScreenCaptureManager.CaptureResult
            ?: return@repeat

        val stillRemaining = mutableListOf<Triple<String, ColorSchema, Int>>()

        // Search ALL remaining troops against the same screenshot — no priority
        for ((troopName, schema, count) in remainingDonations) {
            val target = findMultiColors(schema = schema, byteBuffer = screenBuffer, increment = 1)
            if (target != null) {
                ShowMessage.record("联赛捐兵: 找到 $troopName, 捐赠 x$count")
                ShowMessage("捐赠 $troopName x$count")
                repeat(count) {
                    TouchActions.tap(target.x, target.y, delayTime = 20)
                }
                anyDonated = true
            } else {
                ShowMessage.record("联赛捐兵: 当前屏未找到 $troopName")
                stillRemaining.add(Triple(troopName, schema, count))
            }
        }

        remainingDonations = stillRemaining

        // If some troops still not found and this is the first attempt, swipe right and try again
        if (remainingDonations.isNotEmpty() && screenAttempt == 0) {
            ShowMessage.record("联赛捐兵: 向右滑动后重试剩余 ${remainingDonations.size} 个兵种")
            TouchActions.swipe(287, 290, 1130, 290)
            delayWithMultiplier(400)
        }
    }

    if (!anyDonated) {
        ShowMessage("未捐赠任何兵种")
    }

    // Press back to close the donation popup before switching members
    RunShell.runNoOutput("input keyevent 4")
    ShowMessage("捐赠完成")
}
