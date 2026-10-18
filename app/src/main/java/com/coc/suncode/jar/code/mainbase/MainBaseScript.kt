package com.coc.suncode.jar.code.mainbase


import com.coc.suncode.jar.features.FeatureRuntime
import com.coc.suncode.jar.code.mainbase.others.zoomSmallMainBase
import com.coc.suncode.jar.code.universal.smalltools.enterMainBase
import com.coc.suncode.jar.code.universal.smalltools.getBooleanConfigRuntime
import com.coc.suncode.jar.ui.schema.Schema

suspend fun playMainBase(): Boolean {
    // Each step resolves its independently enabled feature before execution.
    // Disabling a world skips every action in its module, including training and maintenance.
    if (!getBooleanConfigRuntime(Schema.MAIN_BASE_SETTINGS.ENABLED.key)) return true
    if (!enterMainBase()) return false

    zoomSmallMainBase()
    if (!FeatureRuntime.run("main_attack", "train")) return false
    if (!FeatureRuntime.run("main_attack", "attack")) return false
    if (!FeatureRuntime.run("obstacles", "main")) return false
    if (!FeatureRuntime.run("clan", "join")) return false
    if (!FeatureRuntime.run("clan", "request")) return false
    if (!FeatureRuntime.run("clan", "donate")) return false
    if (!FeatureRuntime.run("upgrade", "main_walls")) return false
    if (!FeatureRuntime.run("upgrade", "main_buildings")) return false
    if (!FeatureRuntime.run("main_research", "research")) return false
    if (!FeatureRuntime.run("heroes", "place")) return false
    if (!FeatureRuntime.run("tutorial", "check")) return false
//    if (!upgradeGears()) return false
    return true
}
