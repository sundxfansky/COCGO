package com.coc.suncode.core.system.input

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.coc.suncode.core.util.basic.ShowMessage
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class RootTouchProcessTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val received = Collections.synchronizedList(mutableListOf<TouchSnapshot>())
    private val protocol = Collections.synchronizedList(mutableListOf<String>())
    private lateinit var surface: View
    private var originX = 0
    private var originY = 0

    @Before
    fun createTouchSurface() {
        // Keep every injected event inside this test activity instead of a real automation target.
        compose.setContent {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    object : View(context) {
                        override fun onTouchEvent(event: MotionEvent): Boolean {
                            received += TouchSnapshot(
                                action = event.actionMasked,
                                actionIndex = event.actionIndex,
                                downTime = event.downTime,
                                eventTime = event.eventTime,
                                ids = List(event.pointerCount) { event.getPointerId(it) },
                                x = event.x,
                                y = event.y,
                                rawX = event.rawX,
                                rawY = event.rawY,
                                deviceId = event.deviceId,
                                source = event.source,
                                flags = event.flags,
                                coordinates = List(event.pointerCount) { pointer ->
                                    "id=${event.getPointerId(pointer)} local=${event.getX(pointer)},${event.getY(pointer)}" +
                                        " raw=${event.getRawX(pointer)},${event.getRawY(pointer)} pressure=${event.getPressure(pointer)}"
                                },
                                history = List(event.historySize) { history ->
                                    "time=${event.getHistoricalEventTime(history)} " + List(event.pointerCount) { pointer ->
                                        "id=${event.getPointerId(pointer)} " +
                                            "xy=${event.getHistoricalX(pointer, history)},${event.getHistoricalY(pointer, history)}"
                                    }.joinToString()
                                },
                                historicalX = List(event.historySize) { event.getHistoricalX(0, it) },
                                historicalY = List(event.historySize) { event.getHistoricalY(0, it) }
                            )
                            return true
                        }
                    }.also { surface = it }
                }
            )
        }
        compose.runOnIdle {
            val location = IntArray(2)
            surface.getLocationOnScreen(location)
            originX = location[0] + surface.width / 2
            originY = location[1] + surface.height / 2
            protocol += "SURFACE location=${location.toList()} size=${surface.width}x${surface.height}" +
                " matrix=${surface.matrix.toShortString()} metrics=${surface.resources.displayMetrics}"
        }
    }

    @After
    fun reportRuntimeEvidence() {
        // Keep protocol and unmodified event evidence available even when an assertion times out.
        synchronized(protocol) { protocol.toList() }.forEach { line ->
            ShowMessage.record(line, tag = "RootTouchProcessTest")
        }
        snapshots().forEachIndexed { index, event ->
            ShowMessage.record("EVENT $index $event", tag = "RootTouchProcessTest")
        }
    }

    @Test
    fun configuredTargetReceivesTapAndSwipe() {
        Worker().use { worker ->
            worker.targetSelf()
            sendAndAwait(worker, "DOWN\t81\t$originX\t$originY", MotionEvent.ACTION_DOWN)
            sendAndAwait(worker, "UP\t81", MotionEvent.ACTION_UP)
            sendAndAwait(worker, "DOWN\t82\t$originX\t$originY", MotionEvent.ACTION_DOWN)
            sendAndAwait(
                worker,
                "MOVE\t82\t${originX + 60}\t${originY + 40}",
                MotionEvent.ACTION_MOVE
            )
            sendAndAwait(worker, "UP\t82", MotionEvent.ACTION_UP)
        }

        val events = snapshots()
        val evidence = diagnostics()
        assertEquals(evidence, 5, events.size)
        assertEquals(evidence, events[0].downTime, events[1].downTime)
        assertEquals(evidence, events[2].downTime, events[3].downTime)
        assertEquals(evidence, events[2].downTime, events[4].downTime)
        // Android may resample MOVE beyond its injected sample; history preserves the original.
        val moveSamples = listOf(events[3].x to events[3].y) +
            events[3].historicalX.zip(events[3].historicalY)
        assertTrue(evidence, moveSamples.any { (sampleX, sampleY) ->
            abs(sampleX - events[2].x - 60f) <= 1f &&
                abs(sampleY - events[2].y - 40f) <= 1f
        })
        assertEquals(evidence, 60f, events[4].x - events[2].x, 1f)
        assertEquals(evidence, 40f, events[4].y - events[2].y, 1f)
        assertTrue(evidence, events.all { it.ids == listOf(0) })
    }

    @Test
    fun wrongUidAndBackgroundPackageAreRejectedWithoutTouchingForeground() {
        Worker().use { worker ->
            val context = instrumentation.targetContext
            // The shell UID cannot match the installed test application's UID.
            worker.expectOk("TARGET\t${context.packageName}\t2000")
            val wrongUidReply = worker.command("DOWN\t1\t$originX\t$originY")
            assertTrue("Wrong UID reply: $wrongUidReply\n${diagnostics()}", wrongUidReply.startsWith("ERR\t"))
            instrumentation.waitForIdleSync()
            assertTrue("A mismatched UID must not receive a screen touch. ${diagnostics()}", snapshots().isEmpty())

            worker.expectOk("TARGET\tcom.android.settings\t${context.applicationInfo.uid}")
            val backgroundReply = worker.command("DOWN\t1\t$originX\t$originY")
            assertTrue("Background package reply: $backgroundReply\n${diagnostics()}", backgroundReply.startsWith("ERR\t"))
            instrumentation.waitForIdleSync()
            assertTrue("A background package must not receive a screen touch. ${diagnostics()}", snapshots().isEmpty())

            worker.targetSelf()
            sendAndAwait(worker, "DOWN\t1\t$originX\t$originY", MotionEvent.ACTION_DOWN)
            sendAndAwait(worker, "UP\t1", MotionEvent.ACTION_UP)
        }
        assertEquals(diagnostics(), 2, snapshots().size)
    }

    @Test
    fun multiplePointersShareDownTimeAndCancelAllowsNextGesture() {
        Worker().use { worker ->
            worker.targetSelf()
            sendAndAwait(worker, "DOWN\t9001\t$originX\t$originY", MotionEvent.ACTION_DOWN)
            sendAndAwait(
                worker,
                "DOWN\t42\t${originX + 30}\t${originY + 30}",
                MotionEvent.ACTION_POINTER_DOWN
            )
            sendAndAwait(
                worker,
                "MOVE\t9001\t${originX - 20}\t$originY",
                MotionEvent.ACTION_MOVE
            )
            sendAndAwait(worker, "UP\t9001", MotionEvent.ACTION_POINTER_UP)
            sendAndAwait(
                worker,
                "MOVE\t42\t${originX + 50}\t${originY + 50}",
                MotionEvent.ACTION_MOVE
            )
            sendAndAwait(worker, "CANCEL", MotionEvent.ACTION_CANCEL)
            sendAndAwait(worker, "DOWN\t7\t$originX\t$originY", MotionEvent.ACTION_DOWN)
            sendAndAwait(worker, "UP\t7", MotionEvent.ACTION_UP)
        }

        val events = snapshots()
        val evidence = diagnostics()
        assertEquals(evidence, 8, events.size)
        assertTrue(evidence, events.take(5).all { it.downTime == events.first().downTime })
        assertEquals(evidence, listOf(0, 1), events[1].ids)
        assertEquals(evidence, 1, events[1].actionIndex)
        assertEquals(evidence, listOf(0, 1), events[3].ids)
        assertEquals(evidence, 0, events[3].actionIndex)
        assertEquals(evidence, listOf(1), events[4].ids)
        // Android may synthesize CANCEL with reset pointer metadata and a new downTime.
        assertEquals(evidence, MotionEvent.ACTION_CANCEL, events[5].action)
        assertEquals(evidence, listOf(0), events[6].ids)
        assertEquals(evidence, events[6].downTime, events[7].downTime)
        assertTrue(evidence, events[6].downTime >= events[5].eventTime)
        assertTrue(evidence, events.zipWithNext().all { (before, after) -> before.eventTime <= after.eventTime })
    }

    private fun sendAndAwait(worker: Worker, command: String, action: Int) {
        val previousSize = received.size
        worker.expectOk(command)
        compose.waitUntil(timeoutMillis = 5000) { received.size > previousSize }
        assertEquals("After $command\n${diagnostics()}", action, snapshots().last().action)
    }

    private fun snapshots(): List<TouchSnapshot> = synchronized(received) { received.toList() }

    private fun diagnostics(): String {
        val trace = synchronized(protocol) { protocol.joinToString("\n") }
        return "Protocol:\n$trace\nEvents:\n${snapshots().joinToString("\n")}"
    }

    private data class TouchSnapshot(
        val action: Int,
        val actionIndex: Int,
        val downTime: Long,
        val eventTime: Long,
        val ids: List<Int>,
        val x: Float,
        val y: Float,
        val rawX: Float,
        val rawY: Float,
        val deviceId: Int,
        val source: Int,
        val flags: Int,
        val coordinates: List<String>,
        val history: List<String>,
        val historicalX: List<Float>,
        val historicalY: List<Float>
    )

    private inner class Worker : Closeable {
        private val reader: BufferedReader
        private val writer: BufferedWriter
        private val responses = Executors.newSingleThreadExecutor()

        init {
            val apk = instrumentation.targetContext.applicationInfo.sourceDir
            // UiAutomation tokenizes this command directly instead of interpreting shell quotes.
            check(apk.none { it.isWhitespace() }) { "APK paths containing whitespace are unsupported" }
            val launch = "/system/bin/env CLASSPATH=$apk /system/bin/app_process /system/bin ${RootTouchProcess::class.java.name}"
            val descriptors = instrumentation.uiAutomation.executeShellCommandRw(launch)
            reader = BufferedReader(InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(descriptors[0])))
            writer = BufferedWriter(OutputStreamWriter(ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1])))
            try {
                val ready = readLine()
                assertTrue("Worker must initialize: $ready", ready.startsWith("SUN_TOUCH_READY\t1\t"))
            } catch (error: Throwable) {
                release()
                throw error
            }
        }

        fun targetSelf() {
            val context = instrumentation.targetContext
            expectOk("TARGET\t${context.packageName}\t${context.applicationInfo.uid}")
        }

        fun expectOk(command: String) {
            val reply = command(command)
            assertEquals("Command failed: $command; reply: $reply\n${diagnostics()}", "OK", reply)
        }

        fun command(command: String): String {
            assertTrue("Unexpected pending reply before $command\n${diagnostics()}", !reader.ready())
            protocol += "${SystemClock.uptimeMillis()} SEND $command"
            writer.write(command)
            writer.newLine()
            writer.flush()
            return readLine()
        }

        private fun readLine(): String = responses.submit<String> {
            reader.readLine() ?: error("Root touch worker exited before replying")
        }.get(10, TimeUnit.SECONDS).also { protocol += "${SystemClock.uptimeMillis()} RECV $it" }

        override fun close() {
            try {
                expectOk("QUIT")
            } finally {
                release()
            }
        }

        private fun release() {
            responses.shutdownNow()
            runCatching { writer.close() }
            runCatching { reader.close() }
        }
    }
}
