@file:Suppress("SameParameterValue")

package com.coc.zkqcode.loadjar

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.coc.zkqcode.interfaces.MainCode
import com.coc.zkqcode.core.data.database.GlobalVars
import com.coc.zkqcode.statehelper.AppMode
import com.coc.zkqcode.statehelper.AppStateManager
import dalvik.system.DexClassLoader
import com.coc.zkqcode.jar.ui.EnterMainCode
import java.io.File

class Loadjar(private val context: Context) {

    private var pluginUI: MainCode? = null

    /**
     * Main composable function that handles all loading logic and displays the UI
     */
    @Composable
    fun LoadAndShowUI(loadStatus: String, onClose: () -> Unit) {
        if (loadStatus == "Plugin loaded successfully") {
            // Load the UI from jar
            pluginUI?.ShowMainUI(context, onClose)
        } else {
            Column(modifier = Modifier.background(Color.White)) {
                Text(text = loadStatus)
                Button(onClick = {
                    AppStateManager.setMode(AppMode.Run)
                    onClose()
                }) {
                    Text(text = "关闭悬浮窗")
                }
            }
        }
    }

    /**
     * Start the plugin loading process
     */
    fun startLoading(assetFileName: String = "code.jar", onStatusChange: (String) -> Unit) {
        onStatusChange("加载中...")
        // Create assets folder and extract assets (skipping existing .jar files)
        extractAllAssets()

        val assetsDir = File(context.filesDir, "assets")
        val assetList = assetsDir.list() ?: emptyArray()

        // Find the unencrypted jar with the highest timestamp number (latest build, dev/debug)
        val timestampJar = assetList
            .filter { it.endsWith(".jar") && it.removeSuffix(".jar").toLongOrNull() != null }
            .maxByOrNull { it.removeSuffix(".jar").toLong() }

        val success = if (timestampJar != null) {
            loadPluginFromAssets(timestampJar)
        } else if (assetList.contains(assetFileName)) {
            loadPluginFromAssets(assetFileName)
        } else {
            // Development builds include the plugin classes in the main APK.
            loadBundledPlugin()
        }

        val finalStatus = if (success) {
            "Plugin loaded successfully"
        } else {
            "Failed to load plugin"
        }
        onStatusChange(finalStatus)
    }

    /**
     * Extract jar/dex from assets to private directory and load the plugin UI
     */
    private fun loadPluginFromAssets(assetFileName: String): Boolean {

        return try {
            // Get private directory for extracted files
            val privateDir = File(context.filesDir, "assets")
            val dexOutputDir = context.codeCacheDir

            // Load jar from the assets directory in filesDir
            val jarFile = File(privateDir, assetFileName)

            if (!jarFile.exists()) {
                // File not found in private directory
                return false
            }

            // Android 16+ requires DEX files to be non-writable
            jarFile.setReadOnly()

            // Load the jar using DexClassLoader
            val classLoader = DexClassLoader(
                jarFile.absolutePath, dexOutputDir.absolutePath, null, context.classLoader
            )

            // Load the plugin implementation class
            val pluginClass = classLoader.loadClass("com.coc.zkqcode.jar.ui.EnterMainCode")
            val instance = pluginClass.getDeclaredConstructor().newInstance() as MainCode
            pluginUI = instance
            GlobalVars.pluginUI = instance

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun loadBundledPlugin(): Boolean {
        return try {
            val instance = EnterMainCode()
            pluginUI = instance
            GlobalVars.pluginUI = instance
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Create assets folder and extract assets into it, skipping .jar files that already exist
     */
    private fun extractAllAssets(): File {
        val privateDir = context.filesDir
        val assetsDir = File(privateDir, "assets")

        if (!assetsDir.exists()) {
            assetsDir.mkdirs()
        }

        try {
            val assetList = context.assets.list("") ?: emptyArray()
            for (assetName in assetList) {

                // --- MODIFIED FILTER RULE ---
                // Only proceed if the file ends with .jar or .tflite (case-insensitive)
                if (!assetName.endsWith(".jar", ignoreCase = true) && !assetName.endsWith(".tflite", ignoreCase = true)) {
                    continue
                }
                // ----------------------------

                try {
                    val outputFile = File(assetsDir, assetName)
                    // If it's a jar, and it already exists, don't overwrite it
                    if (assetName.endsWith(".jar", ignoreCase = true) && outputFile.exists()) {
                        // Ensure existing JARs are read-only (required by Android 16+)
                        outputFile.setReadOnly()
                        continue
                    }

                    context.assets.open(assetName).use { input ->
                        outputFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    // Make newly extracted JARs read-only (required by Android 16+)
                    if (assetName.endsWith(".jar", ignoreCase = true)) {
                        outputFile.setReadOnly()
                    }
                } catch (e: Exception) {
                    // This handles cases where .jar might be a directory name (unlikely but safe)
                    println("Failed to copy asset: $assetName, error: $e")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return assetsDir
    }
}
