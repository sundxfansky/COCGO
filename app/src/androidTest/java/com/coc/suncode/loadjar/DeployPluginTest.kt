package com.coc.suncode.loadjar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** One-shot deployment helper for the authorized local debug device. */
@RunWith(AndroidJUnit4::class)
class DeployPluginTest {
    @Test
    fun copyAndLoadCurrentPlugin() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val plugin = File(target.filesDir, "assets/987654322.jar")
        plugin.parentFile?.mkdirs()
        instrumentation.context.assets.open("987654322.jar").use { input ->
            plugin.outputStream().use { output -> input.copyTo(output) }
        }
        assertTrue(Loadjar(target).loadFile(plugin, requireSignature = false))
    }
}
