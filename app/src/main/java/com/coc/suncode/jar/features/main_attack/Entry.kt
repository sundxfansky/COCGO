package com.coc.suncode.jar.features.main_attack

import android.content.Context
import com.coc.suncode.interfaces.FeaturePlugin
import com.coc.suncode.interfaces.FeatureResult
import com.coc.suncode.jar.code.mainbase.attack.mainBaseAttack
import com.coc.suncode.jar.code.mainbase.attack.mainBaseTrainTroops

/** Feature entry point; shared state is resolved through the parent core loader. */
class Entry : FeaturePlugin {
    override suspend fun execute(context: Context, action: String, account: Int): FeatureResult {
        val success = when (action) {
            "train" -> mainBaseTrainTroops()
            "attack" -> mainBaseAttack()
            else -> error("Unsupported action: $action")
        }
        return if (success) FeatureResult.SUCCESS else FeatureResult.FAILED
    }
}
