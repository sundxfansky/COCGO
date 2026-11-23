package com.coc.suncode.core.util.basic

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
class ClipboardReadTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun focusActivityReadsTextAndTheClipboardTimestamp() {
        lateinit var clipboard: ClipboardManager
        compose.setContent {
            val context = LocalContext.current
            clipboard = context.getSystemService(ClipboardManager::class.java)
            Text("Clipboard test")
        }
        val json = """{"buildings":[{"data":1000001,"lvl":11}]}"""
        val started = System.currentTimeMillis()
        var previous: ClipData? = null
        compose.runOnIdle {
            previous = clipboard.primaryClip
            clipboard.setPrimaryClip(ClipData.newPlainText("test", json))
        }
        try {
            assertTrue(ClipboardHelper.isAvailable())
            val result = runBlocking { ClipboardHelper.readClipboard() }
            assertNotNull(result)
            assertEquals(json, result?.text)
            assertTrue((result?.timestampMillis ?: 0L) >= started)
        } finally {
            compose.runOnIdle {
                val saved = previous
                if (saved == null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(saved)
            }
        }
    }
}
