package com.coc.suncode.jar.code.universal.buildings.walls

import com.coc.suncode.core.util.basic.delayWithMultiplier
import com.coc.suncode.jar.code.universal.buildings.BaseType
import com.coc.suncode.jar.code.universal.buildings.WorkerAndResearch
import com.coc.suncode.jar.code.universal.buildings.upgrade.UpgradeResult
import com.coc.suncode.jar.code.universal.buildings.upgrade.WallType
import com.coc.suncode.jar.code.universal.buildings.upgrade.upgradeAllExistingBuildings
import com.coc.suncode.jar.code.universal.smalltools.getBooleanConfigRuntime
import com.coc.suncode.jar.code.universal.smalltools.getConfigRuntime
import com.coc.suncode.core.util.fileactions.LogHelper.logAndRestart
import com.coc.suncode.jar.ui.schema.Schema
import com.coc.suncode.core.util.basic.ShowMessage

suspend fun upgradeWalls(currentBase: BaseType): Boolean {
    // Check whether wall upgrade is enabled for the current base
    val wallUpgradeKey = when (currentBase) {
        BaseType.Main -> Schema.MAIN_BASE_SETTINGS.WALL_UPGRADE_SETTINGS.key
        BaseType.Builder -> Schema.BUILDER_BASE_SETTINGS.BUILDER_BASE_WALL_UPGRADE_SETTINGS.key
    }
    if (!getBooleanConfigRuntime(wallUpgradeKey)) {
        return true
    }
    val workerNumber = WorkerAndResearch.detectWorkerNumber(currentBase)

    if (workerNumber.available < 1 || workerNumber.total < 1) {
        return true
    }
    // Use the appropriate threshold key and display name based on the current base type
    val (wallThresholdKey, wallThresholdName) = when (currentBase) {
        BaseType.Main -> Schema.MAIN_BASE_SETTINGS.UPGRADE_WALL_THRESHOLD.key to
            Schema.MAIN_BASE_SETTINGS.UPGRADE_WALL_THRESHOLD.displayName
        BaseType.Builder -> Schema.BUILDER_BASE_SETTINGS.BUILDER_BASE_UPGRADE_WALL_THRESHOLD.key to
            Schema.BUILDER_BASE_SETTINGS.BUILDER_BASE_UPGRADE_WALL_THRESHOLD.displayName
    }
    val thresholds = 25.coerceAtLeast(getConfigRuntime(wallThresholdKey).toIntOrNull()
        ?: logAndRestart("$wallThresholdName 必须是数字，请检查配置"))
    val startTime = System.currentTimeMillis()
    val timeoutMs = 5 * 60 * 1000L // 5 minutes

    // 1. Gold Upgrade Loop
    while (System.currentTimeMillis() - startTime < timeoutMs) {
        val currentResourcePercentage = calculateResourcesPercentage(currentBase)
        ShowMessage("当前金币百分比${currentResourcePercentage.gold}\n设置阈值${thresholds}")
        
        if (currentResourcePercentage.gold < thresholds) break
        
        when (upgradeAllExistingBuildings(listOf("城墙"), currentBase, skipOrdering = true, WallType.Gold)) {
            UpgradeResult.Failure -> return false
            UpgradeResult.StopLoop -> break
            UpgradeResult.Success -> {}
        }
        delayWithMultiplier(300)
    }

    // 2. Elixir Upgrade Loop
    while (System.currentTimeMillis() - startTime < timeoutMs) {
        val currentResourcePercentage = calculateResourcesPercentage(currentBase)
        ShowMessage("当前圣水百分比${currentResourcePercentage.elixir}\n设置阈值${thresholds}")
        
        if (currentResourcePercentage.elixir < thresholds) break
        
        when (upgradeAllExistingBuildings(listOf("城墙"), currentBase, skipOrdering = true, WallType.Elixir)) {
            UpgradeResult.Failure -> return false
            UpgradeResult.StopLoop -> break
            UpgradeResult.Success -> {}
        }
        delayWithMultiplier(300)
    }
    return true
}
