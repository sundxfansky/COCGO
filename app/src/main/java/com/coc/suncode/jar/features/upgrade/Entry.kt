package com.coc.suncode.jar.features.upgrade

import android.content.Context
import com.coc.suncode.interfaces.FeaturePlugin
import com.coc.suncode.interfaces.FeatureResult
import com.coc.suncode.jar.code.universal.buildings.BaseType
import com.coc.suncode.jar.code.universal.buildings.upgrade.upgradeBuildings
import com.coc.suncode.jar.code.universal.buildings.walls.upgradeWalls

/** Feature entry point; shared state is resolved through the parent core loader. */
class Entry : FeaturePlugin {
    override suspend fun execute(context: Context, action: String, account: Int): FeatureResult {
        val success = when (action) {
            "main_walls" -> upgradeWalls(BaseType.Main)
            "main_buildings" -> upgradeBuildings(BaseType.Main)
            "builder_walls" -> upgradeWalls(BaseType.Builder)
            "builder_buildings" -> upgradeBuildings(BaseType.Builder)
            else -> error("Unsupported action: $action")
        }
        return if (success) FeatureResult.SUCCESS else FeatureResult.FAILED
    }
}
