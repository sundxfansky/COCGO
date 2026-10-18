package com.coc.suncode.core.ui.floatingwindows

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.util.basic.ShowMessage
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise real overlay windows, including the native right-edge resize behavior. */
@RunWith(AndroidJUnit4::class)
class ControlWindowAnimationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var overlay: ComposeView
    private val previousPlugin = GlobalVars.pluginUI
    private val previousPlaying = GlobalVars.isPlaying.value
    private val previousLogVisibility = ShowMessage.isLogWindowVisible()

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
        GlobalVars.pluginUI = null
        GlobalVars.isPlaying.value = true
        compose.mainClock.autoAdvance = false
    }

    @After
    fun restore() {
        GlobalVars.pluginUI = previousPlugin
        GlobalVars.isPlaying.value = previousPlaying
        ShowMessage.setLogOverlayVisible(previousLogVisibility)
    }

    @Test fun leftEdgeOpensAndClosesWithOneTapAndCanReverseMidAnimation() = verifyEdge(false)
    @Test fun rightEdgeOpensAndClosesWithOneTapAndCanReverseMidAnimation() = verifyEdge(true)

    private fun verifyEdge(right: Boolean) {
        mountOverlay(right)
        val hidden = frame()
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
        capture(right, "half")

        tapIcon()
        val opening = sampleAnimation()
        val expanded = opening.last()
        verifyFrames(hidden, opening, right, expanding = true)
        assertEquals(hidden.icon.width * 2f, expanded.icon.width, 1f)
        assertTrue(expanded.width >= expanded.icon.width * 6f)
        capture(right, "expanded")

        tapIcon()
        val closing = sampleAnimation()
        verifyFrames(expanded, closing, right, expanding = false)
        assertEquals(hidden.width, closing.last().width)
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()

        // A second tap during expansion must reverse from the current geometry without a reset.
        tapIcon()
        compose.mainClock.advanceTimeBy(128)
        val partial = frame()
        assertTrue(partial.width in (hidden.width + 1) until expanded.width)
        capture(right, "opening")
        tapIcon()
        // WindowManager can finish one resize requested before the released tap reverses the animation.
        val released = frame()
        compose.mainClock.advanceTimeByFrame()
        val reversedStart = frame()
        assertTrue(abs(reversedStart.icon.width - released.icon.width) < released.height / 4f)
        assertTrue(abs(reversedStart.width - released.width) < expanded.width / 4)
        val reversed = sampleAnimation()
        verifyFrames(reversedStart, reversed, right, expanding = false)
        assertEquals(hidden.width, reversed.last().width)

        tapIcon()
        compose.mainClock.advanceTimeBy(320)
        compose.mainClock.advanceTimeBy(2160)
        val beforeIdle = frame()
        val afterIdle = sampleAnimation(24)
        verifyFrames(beforeIdle, afterIdle, right, expanding = false)
        assertEquals(hidden.width, afterIdle.last().width)
        capture(right, "after-idle")
    }

    private fun mountOverlay(right: Boolean) {
        compose.setContent {
            val context = LocalContext.current
            val anchor = LocalView.current
            val parent = rememberCompositionContext()
            val manager = remember { context.getSystemService(WindowManager::class.java) }
            var contentWidth by remember { mutableIntStateOf(WindowManager.LayoutParams.WRAP_CONTENT) }
            val params = remember {
                createControlWindowLayoutParams().apply {
                    gravity = Gravity.TOP or if (right) Gravity.RIGHT else Gravity.LEFT
                    y = 120
                }
            }
            val view = remember {
                ComposeView(context).apply {
                    setParentCompositionContext(parent)
                    setViewTreeLifecycleOwner(anchor.findViewTreeLifecycleOwner())
                    setViewTreeSavedStateRegistryOwner(anchor.findViewTreeSavedStateRegistryOwner())
                    setContent {
                        MaterialTheme {
                            ControlWindow(isAtRightSide = right, onContentWidthChange = { contentWidth = it })
                        }
                    }
                }.also { overlay = it }
            }
            Box(Modifier.fillMaxSize().background(Color(0xFF495C4E)))
            DisposableEffect(view) {
                manager.addView(view, params)
                onDispose {
                    manager.removeViewImmediate(view)
                    view.disposeComposition()
                }
            }
            val windowWidth = contentWidth
            SideEffect {
                if (params.width != windowWidth) {
                    params.width = windowWidth
                    if (view.isAttachedToWindow) manager.updateViewLayout(view, params)
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun tapIcon() {
        compose.onNodeWithContentDescription("Main Icon").performClick()
    }

    private data class Frame(val left: Int, val top: Int, val width: Int, val height: Int, val icon: Rect)

    private fun frame(): Frame {
        val icon = compose.onNodeWithContentDescription("Main Icon").fetchSemanticsNode().boundsInRoot
        var result: Frame? = null
        compose.runOnIdle {
            val location = IntArray(2)
            overlay.getLocationOnScreen(location)
            result = Frame(location[0], location[1], overlay.width, overlay.height, icon)
        }
        return requireNotNull(result)
    }

    private fun sampleAnimation(count: Int = 21): List<Frame> = List(count) {
        compose.mainClock.advanceTimeByFrame()
        frame()
    }

    private fun verifyFrames(start: Frame, frames: List<Frame>, right: Boolean, expanding: Boolean) {
        var previous = start
        val maximumWidth = maxOf(start.width, frames.maxOf { it.width })
        for (current in frames) {
            val startEdge = if (right) start.left + start.width else start.left
            val currentEdge = if (right) current.left + current.width else current.left
            assertEquals("The native window must stay on the same edge on every frame", startEdge, currentEdge)
            assertEquals(start.top, current.top)
            assertEquals("The main icon must never change height", start.height, current.height)
            val delta = current.width - previous.width
            assertTrue("Width must change in only one direction: $previous -> $current", if (expanding) delta >= 0 else delta <= 0)
            assertTrue("The toolbar must pass through intermediate widths: $previous -> $current", abs(delta) < maximumWidth / 4)
            assertTrue("The icon must move smoothly: $previous -> $current", abs(current.icon.width - previous.icon.width) < start.height / 4f)
            val startIconEdge = if (right) start.icon.right + start.left else start.icon.left + start.left
            val currentIconEdge = if (right) current.icon.right + current.left else current.icon.left + current.left
            assertEquals("The icon viewport must stay attached to the edge", startIconEdge, currentIconEdge, 1f)
            previous = current
        }
        assertTrue(GlobalVars.isPlaying.value)
    }

    private fun capture(right: Boolean, state: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // A frozen Compose clock does not wait for WindowManager's surface resize to be presented.
        val presented = CountDownLatch(1)
        instrumentation.runOnMainSync {
            overlay.postOnAnimation { overlay.postOnAnimation { presented.countDown() } }
        }
        assertTrue(presented.await(2, TimeUnit.SECONDS))
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            val side = if (right) "right" else "left"
            File(instrumentation.targetContext.cacheDir, "control-$side-$state.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            bitmap.recycle()
        }
    }
}
