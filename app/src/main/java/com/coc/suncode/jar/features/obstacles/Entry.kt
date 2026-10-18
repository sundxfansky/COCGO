package com.coc.suncode.jar.features.obstacles

import android.content.Context
import com.coc.suncode.interfaces.FeaturePlugin
import com.coc.suncode.interfaces.FeatureResult
import com.coc.suncode.jar.code.mainbase.others.mainBaseRemoveObstacles
import com.coc.suncode.jar.code.builderbase.others.builderBaseRemoveObstacles

/** Keeps this capability replaceable without changing the account scheduler. */
class Entry : FeaturePlugin {
    override suspend fun execute(context: Context, action: String, account: Int): FeatureResult {
        val success = when (action) {
            "main" -> mainBaseRemoveObstacles()
            "builder" -> builderBaseRemoveObstacles()
            else -> error("Unsupported action: $action")
        }
        return if (success) FeatureResult.SUCCESS else FeatureResult.FAILED
    }
}
