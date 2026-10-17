package com.coc.suncode.core.system.input

import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.ui.floatingwindows.BotRuntime
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.core.util.touchactions.TouchActions
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class RootTouchClientTest {
    @get:Rule
    val compose = createComposeRule()

    private val received = Collections.synchronizedList(mutableListOf<TouchSnapshot>())
    private val failure = CompletableDeferred<Throwable>()
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
            failure.complete(error)
        }
    )
    private var wasPlaying = true
    private var x = 0f
    private var y = 0f

    @Before
    fun prepareSyntheticTarget() {
        assertNull("Touch tests must run without a sunserver connection", GlobalVars.serverActions)
        assertNull("No automation may already be running in the isolated app", BotRuntime.job)
        wasPlaying = GlobalVars.isPlaying.value
        GlobalVars.isPlaying.value = true
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Application logging must initialize before root input", ShowMessage.getContext() != null)
        TouchActions.selectTargetPackage(context.packageName)

        // The real client runs under the app UID and obtains root through its production su path.
        compose.setContent {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    object : View(viewContext) {
                        override fun onTouchEvent(event: MotionEvent): Boolean {
                            received += TouchSnapshot(
                                event.actionMasked,
                                event.downTime,
                                event.eventTime,
                                event.getPointerId(0)
                            )
                            return true
                        }
                    }.also { view ->
                        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            val location = IntArray(2)
                            view.getLocationOnScreen(location)
                            x = location[0] + view.width / 2f
                            y = location[1] + view.height / 2f
                        }
                    }
                }
            )
        }
        compose.waitForIdle()
        assertTrue("The synthetic target must have valid screen coordinates", x > 0f && y > 0f)
    }

    @After
    fun releaseRootInput() {
        try {
            runBlocking {
                withTimeout(15_000) {
                    BotRuntime.stop()
                    TouchActions.releaseAllPointers()
                }
            }
        } finally {
            RootTouchClient.close()
            scope.cancel()
            GlobalVars.isPlaying.value = wasPlaying
        }
        assertNull("Touch input must not initialize or depend on sunserver", GlobalVars.serverActions)
    }

    @Test
    fun touchActionsClickThroughRootWithoutSunserver() {
        runBlocking {
            withTimeout(15_000) { TouchActions.touchDown(x, y, 19) }
        }
        awaitEventCount(1)
        runBlocking {
            withTimeout(10_000) { TouchActions.touchUp(19) }
        }
        awaitEventCount(2)

        val events = snapshots()
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), events.map { it.action })
        assertEquals(events[0].downTime, events[1].downTime)
        assertTrue(events.all { it.pointerId == 0 })
        assertNull(GlobalVars.serverActions)
    }

    @Test
    fun stoppingBotCancelsLongPressAndNextGestureStartsNormally() {
        val started = CompletableDeferred<Unit>()
        runBlocking {
            withTimeout(10_000) {
                // This lambda deliberately replaces the plugin entry point with one cancellable press.
                BotRuntime.start(scope) {
                    TouchActions.touchDown(x, y, 19)
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        compose.waitUntil(timeoutMillis = 15_000) {
            received.isNotEmpty() || !GlobalVars.isPlaying.value || failure.isCompleted
        }
        assertTrue("Long press did not start. ${diagnostics()}", received.isNotEmpty())
        runBlocking {
            withTimeout(10_000) {
                started.await()
                BotRuntime.stop()
            }
        }
        awaitEventCount(2)
        assertNull("Stopping must join the automation job", BotRuntime.job)
        assertEquals(MotionEvent.ACTION_CANCEL, snapshots()[1].action)

        // Reusing the pointer ID detects a stale DOWN in either the client or its root worker.
        runBlocking {
            withTimeout(10_000) { TouchActions.touchDown(x, y, 19) }
        }
        awaitEventCount(3)
        runBlocking {
            withTimeout(10_000) { TouchActions.touchUp(19) }
        }
        awaitEventCount(4)

        val events = snapshots()
        assertEquals(
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP),
            events.map { it.action }
        )
        // Android can synthesize CANCEL with a new downTime and reset pointer metadata.
        assertTrue(events[1].eventTime >= events[0].eventTime)
        assertEquals(events[2].downTime, events[3].downTime)
        assertTrue(events.filter { it.action != MotionEvent.ACTION_CANCEL }.all { it.pointerId == 0 })
        assertTrue(events.zipWithNext().all { (before, after) -> before.eventTime <= after.eventTime })
        assertNull(GlobalVars.serverActions)
    }

    private fun awaitEventCount(count: Int) {
        compose.waitUntil(timeoutMillis = 5000) { received.size >= count || failure.isCompleted }
        assertTrue("Expected $count touch events. ${diagnostics()}", received.size >= count)
    }

    private fun diagnostics(): String = ShowMessage.logLines.value.takeLast(5).joinToString()

    private fun snapshots(): List<TouchSnapshot> = synchronized(received) { received.toList() }

    private data class TouchSnapshot(val action: Int, val downTime: Long, val eventTime: Long, val pointerId: Int)
}
