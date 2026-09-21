package com.coc.suncode.core.system.checkpermissions

import com.topjohnwu.superuser.NoShellException
import com.topjohnwu.superuser.Shell
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RootShellProviderTest {
    @Test
    fun reusesAnExistingRootShell() {
        val rootShell = FakeShell(Shell.ROOT_SHELL)
        var requests = 0

        val result = RootShellProvider.getRootShell {
            requests++
            rootShell
        }

        assertSame(rootShell, result)
        assertEquals(1, requests)
        assertFalse(rootShell.closed)
    }

    @Test
    fun replacesCachedNonRootShellAfterAuthorization() {
        val cachedShell = FakeShell(Shell.NON_ROOT_SHELL)
        val rootShell = FakeShell(Shell.ROOT_SHELL)
        var requests = 0

        val result = RootShellProvider.getRootShell {
            requests++
            if (requests == 1) cachedShell else {
                assertTrue(cachedShell.closed)
                rootShell
            }
        }

        assertSame(rootShell, result)
        assertEquals(2, requests)
        assertFalse(rootShell.closed)
    }

    @Test
    fun stopsAfterOneRetryWhenAccessRemainsDenied() {
        val shells = List(2) { FakeShell(Shell.NON_ROOT_SHELL) }
        var requests = 0

        val result = RootShellProvider.getRootShell { shells[requests++] }

        assertNull(result)
        assertEquals(2, requests)
        assertTrue(shells.all { it.closed })
    }

    @Test
    fun replacesADeadRootShell() {
        val deadShell = FakeShell(Shell.ROOT_SHELL, alive = false)
        val rootShell = FakeShell(Shell.ROOT_SHELL)
        var requests = 0

        val result = RootShellProvider.getRootShell {
            if (requests++ == 0) deadShell else rootShell
        }

        assertSame(rootShell, result)
        assertTrue(deadShell.closed)
        assertEquals(2, requests)
    }

    @Test
    fun initializationFailureLeavesAccessDenied() {
        val result = RootShellProvider.getRootShell {
            throw NoShellException("Shell initialization failed")
        }

        assertNull(result)
    }

    @Test
    fun cleanupFailureLeavesAccessDenied() {
        val shell = FakeShell(Shell.NON_ROOT_SHELL, failOnClose = true)

        assertNull(RootShellProvider.getRootShell { shell })
    }

    // Only shell lifecycle operations are needed to reproduce libsu's cache behavior.
    private class FakeShell(
        private val shellStatus: Int,
        private val alive: Boolean = true,
        private val failOnClose: Boolean = false
    ) : Shell() {
        var closed = false
            private set

        override fun isAlive(): Boolean = alive && !closed

        override fun getStatus(): Int = if (closed) UNKNOWN else shellStatus

        override fun close() {
            if (failOnClose) throw IOException("Shell cleanup failed")
            closed = true
        }

        override fun execTask(task: Task) = error("Unexpected shell command")

        override fun submitTask(task: Task) = error("Unexpected shell command")

        override fun newJob(): Job = error("Unexpected shell command")

        override fun waitAndClose(timeout: Long, unit: TimeUnit): Boolean {
            close()
            return true
        }
    }
}
