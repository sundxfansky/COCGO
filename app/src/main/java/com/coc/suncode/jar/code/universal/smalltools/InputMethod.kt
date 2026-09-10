package com.coc.suncode.jar.code.universal.smalltools

import com.coc.suncode.core.util.basic.RunShell

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
        println("Input method switched to $imeId")
    } else {
        println("Input method is already default.")
    }
}