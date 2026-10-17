package com.coc.suncode.jar.code.universal

import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.system.screencapture.ScreenCaptureManager
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.core.util.fileactions.LogHelper.logAndRestart
import com.coc.suncode.core.util.touchactions.TouchActions
import com.coc.suncode.jar.code.colorschema.MyColors
import com.coc.suncode.jar.code.universal.colors.findMultiColors
import com.coc.suncode.jar.code.universal.smalltools.checkReconnections
import com.coc.suncode.jar.code.universal.smalltools.getBooleanConfigRuntime
import com.coc.suncode.jar.code.universal.smalltools.isGameAtFront
import com.coc.suncode.jar.code.universal.smalltools.killGame
import com.coc.suncode.jar.code.universal.smalltools.runGame
import com.coc.suncode.jar.code.universal.tutorial.AllTutorials
import com.coc.suncode.jar.ui.schema.Schema
import kotlinx.coroutines.delay


/**
 * Waits for the game to enter the main screen within a specified timeout.
 * Returns true if successful, false if it times out.
 */
suspend fun enterMainScreen(isDoubleCheck: Boolean = false): Boolean {
    // 1. Initialize the start time
    val startTime = System.currentTimeMillis()
    // 2. Get the timeout duration from GlobalVars (assumed to be in seconds)
    // We multiply by 1000 to compare milliseconds to milliseconds
    val timeoutSeconds = GlobalVars.configStates["enter_game_timer"]?.value?.toIntOrNull() ?: logAndRestart("enter main game error, can not get game timer")
    val timeoutMillis = timeoutSeconds * 1000L
    var mainBaseTutorialElements = 0
    // Counter to throttle clickRightBottom to roughly every 5 seconds
    var clickRightBottomCounter = 20
    // Counts consecutive passes where we're neither on the home page nor matched/tapped
    // any known popup schema, i.e. an unrecognized screen is blocking progress.
    var stuckPasses = 0
    GlobalVars.absorbEdge = 0
    while (System.currentTimeMillis() - startTime < timeoutMillis) {
        // 3. Insert your logic to check if the main screen is actually visible
        if (!isGameAtFront()) {
            runGame()
        } else {
            if (isInHomePage()) {
                if (!isDoubleCheck) {
                    ShowMessage("已进入主界面")
                    return true
                }
                if (InGamesVars.currentGameVersion == GameVersion.CN) delay(1500)
                else delay(800)
                if (isInHomePage()) {
                    return true
                }
            }
            if (!checkReconnections()) return false
            ShowMessage("账号${InGamesVars.currentAccountNumber}，倒计时${((timeoutMillis - System.currentTimeMillis() + startTime) / 1000).toInt()}秒")
            val matchedAdOrPopup = closeAdvertisements()

            // Click right bottom roughly every 5 seconds
            if (clickRightBottomCounter++ >= 4) {
                clickRightBottom(5)
                clickRightBottomCounter = 0
            }

            val inTutorial = AllTutorials.checkIsInTutorial(mainBaseTutorialElements)
            if (inTutorial) mainBaseTutorialElements++

            // Diagnose unrecognized screens: not home, nothing matched/tapped, not a known
            // tutorial step. Logging every few passes (not every pass) avoids spamming while
            // still surfacing a screen no current schema covers, e.g. a new reward/dialog page.
            if (!matchedAdOrPopup && !inTutorial) {
                stuckPasses++
                if (stuckPasses % 5 == 0) {
                    ShowMessage.record("账号${InGamesVars.currentAccountNumber}，连续${stuckPasses}次未识别到主界面或已知弹窗，当前画面可能未被任何颜色方案覆盖")
                    dumpUnrecognizedScreen(stuckPasses)
                }
            } else {
                stuckPasses = 0
            }
        }
        // 4. Wait before checking again to save CPU cycles
        delay(1000)
    }

    // Return false if the loop finishes without finding the main screen
    killGame()
    return false
}

// Saves a screenshot so an unrecognized screen (no matching color schema) can be inspected
// after the fact instead of needing to reproduce it live.
// Uses the app's private storage: scoped storage on modern Android rejects direct writes to
// a non-default top-level directory like /sdcard/sun_debug from a regular File/FileOutputStream.
private suspend fun dumpUnrecognizedScreen(stuckPasses: Int) {
    try {
        val bitmap = ScreenCaptureManager.capture(asBitmap = true) as? android.graphics.Bitmap ?: return
        val context = ScreenCaptureManager.getContext() ?: return
        val dir = java.io.File(context.filesDir, "sun_debug")
        if (!dir.exists()) dir.mkdirs()
        val file = java.io.File(dir, "unrecognized_screen_${stuckPasses}_${System.currentTimeMillis()}.png")
        java.io.FileOutputStream(file).use { out -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out) }
        ShowMessage.record("调试：未识别画面截图已保存到 ${file.absolutePath}")
        bitmap.recycle()
    } catch (e: Exception) {
        ShowMessage.record("调试：未识别画面截图保存失败", throwable = e)
    }
}


suspend fun clickRightBottom(times: Int, delayTime: Int = 50) {
    repeat(times) {
        TouchActions.tap(1279, 100, false, delayTime)
    }
}


// Returns true if any known popup/dialog/button schema was matched and tapped this pass,
// so callers can detect "stuck" screens where no candidate schema covers what's on screen.
private suspend fun closeAdvertisements(): Boolean {
    // 1. Capture the screen and cast safely (Use 'var' so we can update it)
    var screenBuffer = ScreenCaptureManager.capture(asBitmap = false) as? ScreenCaptureManager.CaptureResult ?: logAndRestart("failed to take screenshot at close advertisement")
    var matchedAny = false

    // 2. Define the schemas to check against
    val homeSchemas = listOf(
        MyColors.ExclusiveGift,
        MyColors.MorePointCoupon,
        MyColors.GreenConfirm,
        MyColors.CNAd,
        MyColors.ClanChat,
        MyColors.OldShopButton,
        MyColors.NewShopButton,
        MyColors.CNProsperity,
        MyColors.MagicalItem,
        MyColors.CNPuppetAd,
        MyColors.CNBackFromAwards,
        MyColors.CollectChest,
        MyColors.EditModeWrench,
        MyColors.MiddleGreenButton,
        MyColors.ExclusiveGift,
        MyColors.MorePointCoupon,
        MyColors.ChestRewardContinue,
    )

    // 3. Iterate through schemas
    homeSchemas.forEach { schema ->
        // Check if the current schema exists on the current screenBuffer
        val point = findMultiColors(byteBuffer = screenBuffer, schema = schema, increment = 1)

        if (point != null) {
            matchedAny = true
            // If found, perform the tap
            TouchActions.tap(point.x, point.y, delayTime = 1000)
            // 4. Retake the screenBuffer so the next schema check uses the updated screen
            screenBuffer = ScreenCaptureManager.capture(asBitmap = false) as? ScreenCaptureManager.CaptureResult ?: return@forEach // Use return@forEach to skip to next if capture fails
        }
    }
    // Reuse screenBuffer for remaining checks; refresh after each tap
    findMultiColors(byteBuffer = screenBuffer, schema = MyColors.UpgradeTHArrow, increment = 1)?.let {
        matchedAny = true
        TouchActions.tap(it.x + 50, it.y + 100, delayTime = 500)
        screenBuffer = ScreenCaptureManager.capture(asBitmap = false) as? ScreenCaptureManager.CaptureResult ?: logAndRestart("failed to take screenshot at close advertisement")
    }
    findMultiColors(byteBuffer = screenBuffer, schema = MyColors.CollectElixirCartTutorial, increment = 1)?.let {
        matchedAny = true
        TouchActions.tap(644, 377)
        repeat(10) {
            TouchActions.tap(946, 601, delayTime = 300)
        }

    }
    findMultiColors(byteBuffer = screenBuffer, schema = MyColors.TencentChildProtection, increment = 1)?.let {
        matchedAny = true
        TouchActions.tap(1011, 87, delayTime = 500)
        screenBuffer = ScreenCaptureManager.capture(asBitmap = false) as? ScreenCaptureManager.CaptureResult ?: logAndRestart("failed to take screenshot at close advertisement")
    }
    findMultiColors(byteBuffer = screenBuffer, schema = MyColors.DailyLoginReward, increment = 1)?.let {
        matchedAny = true
        if (getBooleanConfigRuntime(Schema.MAIN_BASE_SETTINGS.CLAIM_DAILY_REWARD.key)) {
            TouchActions.tap(622, 492, delayTime = 2000)
        } else {
            TouchActions.tap(it.x, it.y)
        }
        screenBuffer = ScreenCaptureManager.capture(asBitmap = false) as? ScreenCaptureManager.CaptureResult ?: logAndRestart("failed to take screenshot at close advertisement")
    }
    findMultiColors(byteBuffer = screenBuffer, schema = MyColors.ReturnAwards, increment = 1)?.let {
        matchedAny = true
        // Define the coordinate pairs in order of execution
        val tapPoints = listOf(
            257 to 297, 464 to 307, 662 to 305, 267 to 512, 466 to 511, 654 to 515, 882 to 513, 1077 to 101
        )

        // Iterate through points to reduce code redundancy
        tapPoints.forEach { (x, y) ->
            TouchActions.tap(x, y, delayTime = 100)
        }
        screenBuffer = ScreenCaptureManager.capture(asBitmap = false) as? ScreenCaptureManager.CaptureResult ?: logAndRestart("failed to take screenshot at close advertisement")
    }
    findMultiColors(byteBuffer = screenBuffer, schema = MyColors.CancelEditMode, increment = 1)?.let {
        matchedAny = true
        TouchActions.tap(it.x, it.y, delayTime = 500)
        TouchActions.tap(788, 464)
        screenBuffer = ScreenCaptureManager.capture(asBitmap = false) as? ScreenCaptureManager.CaptureResult ?: logAndRestart("failed to take screenshot at close advertisement")
    }
    findMultiColors(byteBuffer = screenBuffer, schema = MyColors.TrainingPage, increment = 1)?.let {
        matchedAny = true
        TouchActions.tap(219, 139, delayTime = 1000)//close training tap
        TouchActions.tap(1232, 65, delayTime = 300)//close training page
    }
    return matchedAny
}

private suspend fun isInHomePage(): Boolean {
    // 1. Capture the screen and cast safely
    val screenBuffer = ScreenCaptureManager.capture(asBitmap = false) as? ScreenCaptureManager.CaptureResult ?: logAndRestart("in isInHomePage, screen capture failed.")

    // 2. Check for the training button presence
    val hasTrainButton = findMultiColors(byteBuffer = screenBuffer, schema = MyColors.TrainTroops, increment = 1) != null
    if (!hasTrainButton) return false

    // 3. Check for any of the worker icons (Main base, Goblin workers, or Builder base)
    val workerSchemas = listOf(
        MyColors.MainBaseWorker, MyColors.MainBaseWorker2, MyColors.MainBaseWorker3, MyColors.GoblinWorker, MyColors.GoblinResearcher, MyColors.BuilderBaseWorker, MyColors.BuilderBaseWorker2
    )

    return workerSchemas.any { findMultiColors(byteBuffer = screenBuffer, schema = it, increment = 1) != null }
}