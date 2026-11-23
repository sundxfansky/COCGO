package com.coc.suncode.jar.code.universal.buildings.upgrade

import com.coc.suncode.jar.code.universal.buildings.WorkerAndResearch
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.core.util.touchactions.TouchActions
import com.coc.suncode.jar.code.universal.buildings.BaseType
import com.coc.suncode.jar.code.universal.buildings.iterateBuilderBaseBuildingUpgradeList

suspend fun builderBaseFindNewBuildings(currentBase: BaseType): Boolean {
    val worker = WorkerAndResearch.findUpgradeEntry(currentBase)
    if (worker != null) {
        TouchActions.tap(worker.x, worker.y, delayTime = 500)
        var found = false
        iterateBuilderBaseBuildingUpgradeList(onDetect = { result ->
            val buildings = result.buildings
            if (buildings.isEmpty()) {
                ShowMessage("未检测到可升级建筑")
            } else {
                val newBuilding = buildings.find { it.name.startsWith("新") }
                if (newBuilding != null) {
                    if (newBuilding.y > 530) return@iterateBuilderBaseBuildingUpgradeList false
                    ShowMessage("检测到新建筑: ${newBuilding.name}, x: ${newBuilding.x}, y: ${newBuilding.y}")
                    TouchActions.tap(newBuilding.x + 20, newBuilding.y + 20, delayTime = 1500)
                    found = true
                    return@iterateBuilderBaseBuildingUpgradeList true
                }
            }
            false
        })
        return found
    }
    return false
}
