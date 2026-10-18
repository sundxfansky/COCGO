package com.coc.suncode.jar.features.heroes

import android.content.Context
import com.coc.suncode.interfaces.FeaturePlugin
import com.coc.suncode.interfaces.FeatureResult
import com.coc.suncode.jar.code.mainbase.herohall.placeHeroBanners
import com.coc.suncode.jar.code.mainbase.herohall.upgradeGearsAndPets

/** Keeps this capability replaceable without changing the account scheduler. */
class Entry : FeaturePlugin {
    override suspend fun execute(context: Context, action: String, account: Int): FeatureResult {
        val success = when (action) {
            "place" -> placeHeroBanners()
            "upgrade" -> upgradeGearsAndPets()
            else -> error("Unsupported action: $action")
        }
        return if (success) FeatureResult.SUCCESS else FeatureResult.FAILED
    }
}
