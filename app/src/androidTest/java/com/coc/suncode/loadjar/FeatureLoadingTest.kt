package com.coc.suncode.loadjar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coc.suncode.core.system.daemon.ServerApkInstaller
import com.coc.suncode.interfaces.FeaturePlugin
import com.coc.suncode.loadjar.features.FeatureArchive
import com.coc.suncode.loadjar.features.FeatureClassLoader
import com.coc.suncode.loadjar.features.FeatureSpec
import com.coc.suncode.loadjar.features.FeatureStore
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Loads actual D8 output without running game actions or modifying installed feature choices. */
@RunWith(AndroidJUnit4::class)
class FeatureLoadingTest {
    @Test fun splitEntriesUseOneSharedCoreAndCannotBindToBundledApkFeatures() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val core = File(context.codeCacheDir, "feature-loader-test.jar")
        ServerApkInstaller.install(core) { instrumentation.context.assets.open("code.jar") }
        val coreLoader = PluginClassLoader(core.path, context.codeCacheDir.path, context.classLoader)
        val shared = coreLoader.loadClass("com.coc.suncode.jar.code.universal.InGamesVars")
        val store = FeatureStore(context)
        ZipFile(core).use { archive ->
            FeatureSpec.read(context).forEach { spec ->
                val bytes = archive.getInputStream(archive.getEntry("features/${spec.id}.jar")).use { it.readBytes() }
                assertTrue(FeatureArchive.metadata(bytes).id == spec.id)
                val feature = store.cache(bytes)
                val loader = FeatureClassLoader(feature.path, context.codeCacheDir.path, coreLoader, spec)
                val entry = loader.loadClass(spec.entry)
                assertTrue(entry.getDeclaredConstructor().newInstance() is FeaturePlugin)
                assertSame(loader, entry.classLoader)
                assertSame(shared, loader.loadClass(shared.name))
                assertNotSame(context.classLoader.loadClass(spec.entry), entry)
                try { coreLoader.loadClass(spec.entry); fail("Core must not define feature classes") }
                catch (_: ClassNotFoundException) { }
            }
        }
    }
}
