package com.coc.suncode.jar.code.mainbase.herohall

import com.coc.suncode.jar.code.universal.colors.findMultiColors
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.core.util.touchactions.TouchActions
import com.coc.suncode.jar.code.colorschema.MyColors
import com.coc.suncode.jar.code.mainbase.upgrade.mainBaseFindBuildButton
import com.coc.suncode.jar.code.universal.InGamesVars
import com.coc.suncode.jar.code.universal.buildings.BaseType
import com.coc.suncode.jar.code.universal.buildings.upgrade.BuildButtonType
import com.coc.suncode.jar.code.universal.buildings.upgrade.tryToFindBuildPosition
import com.coc.suncode.jar.code.universal.clickRightBottom
import com.coc.suncode.jar.code.universal.colors.findMultiColorsUntil
import com.coc.suncode.jar.code.universal.smalltools.StorageKeys
import com.coc.suncode.jar.code.universal.smalltools.checkMemoryFile
import com.coc.suncode.jar.code.universal.smalltools.writeMemory

suspend fun placeHeroBanners(): Boolean {
    val storageKey = StorageKeys.withAccountNumber(
        StorageKeys.PLACE_HERO_BANNERS, InGamesVars.currentAccountNumber
    )

    // Guard: only attempt banner placement once per day (1440 minutes)
    if (!checkMemoryFile(storageKey, 1440)) {
        ShowMessage("账号${InGamesVars.currentAccountNumber}，该账号今日已检测战旗")
        return true
    }
    ShowMessage("准备检测是否卡战旗")
    val result = withHeroHall { placeHeroBannerAction() }

    // Always record completion and return to main screen
    writeMemory(storageKey, (System.currentTimeMillis() / 60_000).toString())
    return result
}

private suspend fun placeHeroBannerAction() {
    val redExclamationMark = findMultiColorsUntil(schemas = listOf(MyColors.RedExclamationMark), duration = 500)
    // If there is no marker, we only need to close the panel.
    if (redExclamationMark == null) {
        clickRightBottom(1)
        return
    }

    // Some hall notifications are reward chests instead of unplaced banners.
    TouchActions.tap(705, 270, delayTime = 300)
    repeat(4) {
        val reward = findMultiColors(MyColors.ClaimHeroHallReward) ?: return@repeat
        TouchActions.tap(reward.x, reward.y, delayTime = 300)
        TouchActions.tap(640, 530, delayTime = 200)
    }
    TouchActions.tap(598, 591, delayTime = 300) // Place hero banner page
    val placeHeroBanner = findMultiColorsUntil(schemas = listOf(MyColors.PlaceBannerButton), duration = 500)

    if (placeHeroBanner != null) {
        TouchActions.tap(placeHeroBanner.x, placeHeroBanner.y, delayTime = 300)
        // Locate the confirmation button (Green Tick).
        var targetTick = mainBaseFindBuildButton(type = BuildButtonType.Tick)
        if (targetTick == null) {
            ShowMessage("建造失败，尝试寻找空位")
            // The shared placement routine owns the touch pointer until it is released.
            targetTick = tryToFindBuildPosition(BaseType.Main)
        }

        if (targetTick != null) {
            ShowMessage("放战旗成功")
            TouchActions.tap(targetTick.x, targetTick.y, delayTime = 300)
        }
    } else {
        ShowMessage("未检测到放战旗按钮")
        clickRightBottom(1)
    }
}
