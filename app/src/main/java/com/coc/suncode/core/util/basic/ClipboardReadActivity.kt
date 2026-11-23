package com.coc.suncode.core.util.basic

import android.app.Activity
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/** A non-exported, short-lived window used only while reading the game clipboard. */
class ClipboardReadActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val close = Runnable { finish() }
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handler.postDelayed(close, ClipboardHelper.FOCUS_TIMEOUT_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !completed) {
            completed = true
            ClipboardHelper.finishRead(
                intent.getLongExtra(ClipboardHelper.REQUEST_ID, -1), getSystemService(ClipboardManager::class.java)
            )
            finish()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(close)
        super.onDestroy()
    }
}
