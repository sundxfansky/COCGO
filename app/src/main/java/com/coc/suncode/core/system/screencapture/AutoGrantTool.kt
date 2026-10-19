package com.coc.suncode.core.system.screencapture

import com.coc.suncode.core.system.accessibility.MyAccessibilityService
import com.coc.suncode.core.system.checkpermissions.AccessibilityPermissionHelper

object AutoGrantTool {
    /** Retain the helper without toggling accessibility services belonging to other apps. */
    suspend fun forceEnableAccessibility(): Boolean {
        val context = ScreenCaptureManager.getContext() ?: return false
        return AccessibilityPermissionHelper.enableAccessibilityWithRoot(
            context.packageName,
            MyAccessibilityService::class.java.name
        )
    }
}
