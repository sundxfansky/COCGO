package com.coc.suncode.core.util.basic

import com.coc.suncode.core.data.database.GlobalVars
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

class LogHistoryTest {
    private lateinit var originalTrees: List<Timber.Tree>
    private var wasPlaying = false
    private var wasSwitchingAccount = false

    @Before
    fun prepareHistory() {
        originalTrees = Timber.forest()
        Timber.uprootAll()
        ShowMessage.clearLogs()
        wasPlaying = GlobalVars.isPlaying.value
        wasSwitchingAccount = GlobalVars.isSwitchingAccount
        // JVM tests never create Android overlay windows.
        ShowMessage.setLogOverlayVisible(true)
    }

    @After
    fun restoreLogging() {
        GlobalVars.isPlaying.value = wasPlaying
        GlobalVars.isSwitchingAccount = wasSwitchingAccount
        ShowMessage.setLogOverlayVisible(false)
        ShowMessage.clearLogs()
        Timber.uprootAll()
        originalTrees.forEach { Timber.plant(it) }
    }

    @Test
    fun earlyRecordAndTimberShareOneHistoryWithoutDuplicateTrees() {
        ShowMessage.record("early startup")
        repeat(5) { LogHistory.ensureInstalled() }
        Timber.tag("worker").i("background event")

        val logs = ShowMessage.logLines.value
        assertEquals(1, Timber.treeCount)
        assertEquals(2, logs.size)
        assertTrue(logs[0].line.endsWith("D/sun_debug: early startup"))
        assertTrue(logs[1].line.endsWith("I/worker: background event"))
    }

    @Test
    fun pausedAndRepeatedMessagesAreStillRecordedExactlyOnce() {
        GlobalVars.isPlaying.value = false
        GlobalVars.isSwitchingAccount = false
        ShowMessage("while paused")
        GlobalVars.isPlaying.value = true
        repeat(2) { ShowMessage("same event") }

        val logs = ShowMessage.logLines.value
        assertEquals(3, logs.size)
        assertTrue(logs[0].line.endsWith("while paused"))
        assertEquals(2, logs.count { it.line.endsWith("same event") })
        assertEquals(3, logs.map { it.id }.toSet().size)
    }

    @Test
    fun concurrentWritersDoNotLoseEvents() {
        val workerCount = 8
        val eventsPerWorker = 100
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workerCount)
        try {
            val workers = (0 until workerCount).map { worker ->
                executor.submit {
                    start.await()
                    repeat(eventsPerWorker) { event -> ShowMessage.record("event-$worker-$event") }
                }
            }
            start.countDown()
            workers.forEach { it.get(15, TimeUnit.SECONDS) }

            val entries = ShowMessage.logLines.value
            val events = entries.map { it.line.substringAfter(": ") }
            val expected = (0 until workerCount).flatMap { worker ->
                (0 until eventsPerWorker).map { event -> "event-$worker-$event" }
            }.toSet()
            assertEquals(workerCount * eventsPerWorker, events.size)
            assertEquals(expected, events.toSet())
            assertEquals(events.size, entries.map { it.id }.toSet().size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun historyKeepsTheNewestThousandEventsAndClearAcceptsNewEvents() {
        repeat(1100) { ShowMessage.record("bounded-$it") }

        val logs = ShowMessage.logLines.value
        assertEquals(1000, logs.size)
        assertTrue(logs.first().line.endsWith("bounded-100"))
        assertTrue(logs.last().line.endsWith("bounded-1099"))

        ShowMessage.clearLogs()
        assertTrue(ShowMessage.logLines.value.isEmpty())
        ShowMessage.record("after clear")
        assertEquals(1, ShowMessage.logLines.value.size)
        val afterClear = ShowMessage.logLines.value.single()
        assertTrue(afterClear.line.endsWith("after clear"))
        assertTrue(afterClear.id > logs.last().id)
    }

    @Test
    fun throwableStackIsIncludedInTheSameEvent() {
        ShowMessage.record("inference failed", throwable = IllegalStateException("model unavailable"))

        val entry = ShowMessage.logLines.value.single().line
        assertTrue(entry.contains("inference failed"))
        assertTrue(entry.contains("IllegalStateException: model unavailable"))
        assertTrue(entry.contains("LogHistoryTest.throwableStackIsIncludedInTheSameEvent"))
    }

    @Test
    fun evictionPreservesTheIdentityOfEveryRetainedEntry() {
        repeat(1000) { ShowMessage.record("repeated event") }
        val before = ShowMessage.logLines.value
        ShowMessage.record("repeated event")
        val after = ShowMessage.logLines.value

        assertEquals(before.drop(1), after.dropLast(1))
        assertTrue(after.last().id > before.last().id)
        assertEquals(1000, after.map { it.id }.toSet().size)
    }
}
