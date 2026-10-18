package com.coc.suncode.loadjar.features

import com.coc.suncode.interfaces.FeatureResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class FeatureDispatcherTest {
    @Test fun disabledFeaturesNeverLoadAndLoginCannotBeSkipped() = runBlocking {
        val dispatcher = FeatureDispatcher({ false }) { error("Disabled feature was loaded") }
        assertEquals(FeatureResult.SKIPPED, dispatcher.execute("main_attack", "attack", 1))
        assertEquals(FeatureResult.FAILED, dispatcher.execute("login", "prepare", 1))
    }

    @Test fun enablingAndReloadingPreserveExplicitSessionBoundaries() = runBlocking {
        var enabled = true
        var loads = 0
        val calls = mutableListOf<String>()
        val dispatcher = FeatureDispatcher({ enabled }) {
            val generation = ++loads
            val action: suspend (String, Int) -> FeatureResult = { action, account ->
                calls += "$generation:$action:$account"
                FeatureResult.SUCCESS
            }
            action
        }
        dispatcher.execute("main_attack", "train", 4)
        dispatcher.execute("main_attack", "attack", 4)
        enabled = false
        dispatcher.execute("main_attack", "attack", 4)
        enabled = true
        dispatcher.reload()
        dispatcher.execute("main_attack", "attack", 5)
        assertEquals(listOf("1:train:4", "1:attack:4", "2:attack:5"), calls)
    }

    @Test fun cancellationAndFailuresAreNeverRetried() = runBlocking {
        val cancellation = CancellationException("stop")
        var calls = 0
        val dispatcher = FeatureDispatcher({ true }) {
            val action: suspend (String, Int) -> FeatureResult = { _, _ -> calls++; throw cancellation }
            action
        }
        try {
            dispatcher.execute("login", "prepare", 1)
            fail("Expected cancellation")
        } catch (error: CancellationException) { assertSame(cancellation, error) }
        assertEquals(1, calls)
    }

    @Test fun failedLoaderDoesNotPoisonTheCache() = runBlocking {
        var loads = 0
        val dispatcher = FeatureDispatcher({ true }) {
            if (++loads == 1) throw IllegalStateException("incompatible")
            val action: suspend (String, Int) -> FeatureResult = { _, _ -> FeatureResult.SUCCESS }
            action
        }
        try { dispatcher.execute("main_attack", "attack", 1); fail("Expected failure") }
        catch (_: IllegalStateException) { }
        assertEquals(FeatureResult.SUCCESS, dispatcher.execute("main_attack", "attack", 1))
        assertEquals(2, loads)
    }
}
