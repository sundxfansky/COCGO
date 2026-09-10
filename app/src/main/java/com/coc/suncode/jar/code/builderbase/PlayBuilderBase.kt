package com.coc.suncode.jar.code.builderbase

import com.coc.suncode.jar.code.builderbase.attack.builderBaseAttack
import com.coc.suncode.jar.code.builderbase.attack.builderBaseTrainWithConditions
import com.coc.suncode.jar.code.builderbase.others.builderBaseRemoveObstacles
import com.coc.suncode.jar.code.builderbase.others.clickOttosOutPost
import com.coc.suncode.jar.code.builderbase.precheck.claimAchievement
import com.coc.suncode.jar.code.builderbase.research.builderBaseResearch
import com.coc.suncode.jar.code.builderbase.resources.collectBuilderBaseResources
import com.coc.suncode.jar.code.universal.buildings.BaseType
import com.coc.suncode.jar.code.universal.buildings.upgrade.upgradeBuildings
import com.coc.suncode.jar.code.universal.buildings.walls.upgradeWalls
import com.coc.suncode.jar.code.universal.smalltools.enterBuilderBase
import com.coc.suncode.jar.code.universal.smalltools.getBooleanConfigRuntime
import com.coc.suncode.jar.ui.schema.Schema

suspend fun playBuilderBase(): Boolean {
    if (!claimAchievement()) return false
    val noBuilderBase = getBooleanConfigRuntime(Schema.BUILDER_BASE_SETTINGS.NO_BUILDER_BASE.key)
    if (noBuilderBase) return true//if no builder base, then directly return.
    if (!enterBuilderBase(true)) return true
    if (!collectBuilderBaseResources()) return false
    if (!enterBuilderBase(false)) return true
    if (!clickOttosOutPost()) return false
    if (!builderBaseRemoveObstacles()) return false
    if (!enterBuilderBase(false)) return true
    if (!upgradeWalls(BaseType.Builder)) return false
    if (!upgradeBuildings(BaseType.Builder)) return false
    if (!builderBaseResearch()) return false
    if (!enterBuilderBase(false)) return true
    if (!builderBaseTrainWithConditions()) return false
    if (!builderBaseAttack()) return false
    return true
}