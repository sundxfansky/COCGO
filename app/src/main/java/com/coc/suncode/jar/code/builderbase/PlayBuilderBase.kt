package com.coc.suncode.jar.code.builderbase

import com.coc.suncode.jar.features.FeatureRuntime
import com.coc.suncode.jar.code.universal.smalltools.enterBuilderBase
import com.coc.suncode.jar.code.universal.smalltools.getBooleanConfigRuntime
import com.coc.suncode.jar.ui.schema.Schema

suspend fun playBuilderBase(): Boolean {
    // The master switch also skips prechecks and auxiliary actions for this world.
    if (!getBooleanConfigRuntime(Schema.BUILDER_BASE_SETTINGS.ENABLED.key)) return true
    if (!FeatureRuntime.run("collection", "achievements")) return false
    if (!enterBuilderBase(true)) return true
    if (!FeatureRuntime.run("collection", "resources")) return false
    if (!enterBuilderBase(false)) return true
    if (!FeatureRuntime.run("collection", "otto")) return false
    if (!FeatureRuntime.run("obstacles", "builder")) return false
    if (!enterBuilderBase(false)) return true
    if (!FeatureRuntime.run("upgrade", "builder_walls")) return false
    if (!FeatureRuntime.run("upgrade", "builder_buildings")) return false
    if (!FeatureRuntime.run("builder_research", "research")) return false
    if (!enterBuilderBase(false)) return true
    if (!FeatureRuntime.run("builder_attack", "train")) return false
    if (!FeatureRuntime.run("builder_attack", "attack")) return false
    return true
}
