package com.coc.suncode.jar.code.universal.smalltools

import com.coc.suncode.core.system.inputmethod.SunInputMethodService
import com.coc.suncode.core.util.basic.RunShell
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.core.util.basic.delayWithMultiplier
import com.coc.suncode.core.util.fileactions.LogHelper.logAndRestart

suspend fun setSUNInputMethod() {
    val imeId = "com.coc.suncode/.core.system.inputmethod.SunInputMethodService"

    // 1. Try to enable (it's fine to execute again even if already enabled)
    RunShell.run("ime enable $imeId")

    // 2. Get current default input method for comparison
    val currentIme = RunShell.runAndGetFirst("settings get secure default_input_method")

    if (currentIme != imeId) {
        // 3. Execute setting
        RunShell.run("ime set $imeId")
        // Verify switch success
        ShowMessage.record("Input method switched to $imeId")
    } else {
        ShowMessage.record("Input method is already default.")
    }
}

/**
 * Types [text] into the currently focused field.
 *
 * Printable ASCII goes through the root shell's `input text`, which needs no custom IME and so
 * also works on emulators that block enabling third-party input methods (e.g. MuMu).
 * Anything else (Chinese, emoji, ...) cannot be injected by `input text`, so it falls back to
 * switching to [SunInputMethodService] and committing the text through its input connection.
 */
suspend fun inputGameText(text: String) {
    if (text.isEmpty()) return
    if (text.all { it in ' '..'~' }) {
        // `input text` reads "%s" as a space; single quotes keep the root shell from expanding the rest.
        val escaped = text.replace(" ", "%s").replace("'", "'\\''")
        RunShell.run("input text '$escaped'")
        return
    }
    ShowMessage.record("文本包含非 ASCII 字符，改用内置输入法输入")
    setSUNInputMethod()
    // The field is already focused; give the system time to bind the new IME to it.
    delayWithMultiplier(300)
    SunInputMethodService.instance?.inputText(text) ?: logAndRestart("获取输入法失败")
}
