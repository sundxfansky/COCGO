package com.coc.suncode.jar.features.collection

import android.content.Context
import com.coc.suncode.interfaces.FeaturePlugin
import com.coc.suncode.interfaces.FeatureResult
import com.coc.suncode.jar.code.builderbase.resources.collectBuilderBaseResources
import com.coc.suncode.jar.code.builderbase.others.clickOttosOutPost
import com.coc.suncode.jar.code.builderbase.precheck.claimAchievement

/** Keeps this capability replaceable without changing the account scheduler. */
class Entry : FeaturePlugin {
    override suspend fun execute(context: Context, action: String, account: Int): FeatureResult {
        val success = when (action) {
            "resources" -> collectBuilderBaseResources()
            "otto" -> clickOttosOutPost()
            "achievements" -> claimAchievement()
            else -> error("Unsupported action: $action")
        }
        return if (success) FeatureResult.SUCCESS else FeatureResult.FAILED
    }
}
