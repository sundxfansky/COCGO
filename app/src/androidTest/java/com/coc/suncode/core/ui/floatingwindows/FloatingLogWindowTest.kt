package com.coc.suncode.core.ui.floatingwindows

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.util.basic.ShowMessage
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class FloatingLogWindowTest {
    @get:Rule
    val compose = createComposeRule()

    private var wasPlaying = true

    @Before
    fun prepare() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (!Settings.canDrawOverlays(context)) {
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow"
                )
            ).use { it.readBytes() }
        }
        assertTrue(Settings.canDrawOverlays(context))
        wasPlaying = GlobalVars.isPlaying.value
        GlobalVars.isPlaying.value = true
        ShowMessage.setLogOverlayVisible(false)
        ShowMessage.clearLogs()
        // Keep the control strip expanded until each test explicitly advances its timer.
        compose.mainClock.autoAdvance = false
    }

    @After
    fun restore() {
        ShowMessage.setLogOverlayVisible(false)
        GlobalVars.isPlaying.value = wasPlaying
    }

    @Test
    fun openingAndClosingLogsFromLeftDoesNotPauseAutomation() {
        verifyOpeningLogs(isAtRightSide = false)
    }

    @Test
    fun openingAndClosingLogsFromRightDoesNotPauseAutomation() {
        verifyOpeningLogs(isAtRightSide = true)
    }

    private fun verifyOpeningLogs(isAtRightSide: Boolean) {
        val underlyingTaps = AtomicInteger()
        compose.setContent {
            MaterialTheme {
                val screenSize = LocalWindowInfo.current.containerSize
                val underlayInteractions = remember { MutableInteractionSource() }
                Box(
                    Modifier.fillMaxSize().background(Color(0xFF495C4E))
                        // A frozen test clock must not leave a ripple in screenshot reference pixels.
                        .clickable(interactionSource = underlayInteractions, indication = null) {
                            underlyingTaps.incrementAndGet()
                        }
                ) {
                    ControlWindow(
                        isAtRightSide = isAtRightSide,
                        screenWidth = screenSize.width,
                        screenHeight = screenSize.height
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Main Icon").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertTrue(GlobalVars.isPlaying.value) }
        compose.onNodeWithContentDescription("日志").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("运行日志").assertIsDisplayed()
        val logWindow = findLogWindow()
        verifyWindowMode(logWindow, interactive = false)
        tapWindowCenter(logWindow)
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(1, underlyingTaps.get()) }
        compose.runOnIdle {
            assertTrue(GlobalVars.isPlaying.value)
            ShowMessage("日志浮层测试消息")
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("日志浮层测试消息", substring = true).assertIsDisplayed()
        captureLogWindow(isAtRightSide)
        verifyCaptureHidesAndRestoresLogWindow(logWindow)

        // Hiding the control strip must not dismiss the independent log window or stop the bot.
        compose.mainClock.advanceTimeBy(3000)
        compose.onNodeWithText("运行日志").assertIsDisplayed()
        compose.runOnIdle { assertTrue(GlobalVars.isPlaying.value) }

        compose.onNodeWithContentDescription("Main Icon").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription("Main Icon").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription("浏览日志").performClick()
        compose.mainClock.advanceTimeByFrame()
        verifyWindowMode(logWindow, interactive = true)
        compose.runOnIdle { assertTrue(GlobalVars.isPlaying.value) }
        try {
            runBlocking { LogOverlayCaptureGuard.setInputActive(true) }
            verifyWindowMode(logWindow, interactive = false)
            compose.runOnIdle { ShowMessage("手势保护期间的新日志") }
            compose.mainClock.advanceTimeByFrame()
            verifyWindowMode(logWindow, interactive = false)
        } finally {
            runBlocking { LogOverlayCaptureGuard.setInputActive(false) }
        }
        verifyWindowMode(logWindow, interactive = true)
        compose.runOnIdle { assertTrue(GlobalVars.isPlaying.value) }
        compose.onNodeWithContentDescription("清空日志").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("暂无日志").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭日志").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("运行日志").assertDoesNotExist()
        compose.runOnIdle { assertTrue(GlobalVars.isPlaying.value) }
        assertTrue(!LogOverlayCaptureGuard.isLogVisible())
    }

    private fun findLogWindow(): View {
        var result: View? = null
        compose.runOnIdle {
            result = WindowInspector.getGlobalWindowViews().firstOrNull {
                (it.layoutParams as? WindowManager.LayoutParams)?.title == LOG_OVERLAY_WINDOW_TITLE
            }
        }
        return requireNotNull(result) { "Log window was not added to WindowManager" }
    }

    private fun verifyWindowMode(window: View, interactive: Boolean) {
        compose.runOnIdle {
            val params = window.layoutParams as WindowManager.LayoutParams
            assertTrue(params.alpha > 0f && params.alpha <= 0.75f)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
            assertEquals(!interactive, params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        }
    }

    private fun tapWindowCenter(window: View) {
        val position = IntArray(2)
        var x = 0f
        var y = 0f
        compose.runOnIdle {
            window.getLocationOnScreen(position)
            x = position[0] + window.width / 2f
            y = position[1] + window.height / 2f
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
            val event = MotionEvent.obtain(downTime, downTime + index * 10, action, x, y, 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(automation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
    }

    private fun verifyCaptureHidesAndRestoresLogWindow(window: View) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val position = IntArray(2)
        var centerX = 0
        var centerY = 0
        compose.runOnIdle {
            window.getLocationOnScreen(position)
            centerX = position[0] + window.width / 2
            centerY = position[1] + window.height / 2
        }
        val visibleScreenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertTrue(
                "The visible log surface must cover the reference pixel before guarded capture",
                visibleScreenshot.getPixel(centerX, centerY) != Color(0xFF495C4E).toArgb()
            )
        } finally {
            visibleScreenshot.recycle()
        }
        runBlocking {
            LogOverlayCaptureGuard.withoutLogs {
                instrumentation.runOnMainSync { assertEquals(View.INVISIBLE, window.visibility) }
                val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    assertEquals(Color(0xFF495C4E).toArgb(), screenshot.getPixel(centerX, centerY))
                } finally {
                    screenshot.recycle()
                }
            }
            val failure = runCatching {
                LogOverlayCaptureGuard.withoutLogs { error("capture-test-failure") }
            }.exceptionOrNull()
            assertEquals("capture-test-failure", failure?.message)
            withTimeout(2000) {
                val captureStarted = CompletableDeferred<Unit>()
                val capture = launch {
                    LogOverlayCaptureGuard.withoutLogs {
                        captureStarted.complete(Unit)
                        awaitCancellation()
                    }
                }
                captureStarted.await()
                capture.cancelAndJoin()
            }
        }
        compose.runOnIdle { assertEquals(View.VISIBLE, window.visibility) }
    }

    private fun captureLogWindow(isAtRightSide: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            val side = if (isAtRightSide) "right" else "left"
            val file = File(instrumentation.targetContext.cacheDir, "floating-log-$side.png")
            file.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            screenshot.recycle()
        }
    }
}
