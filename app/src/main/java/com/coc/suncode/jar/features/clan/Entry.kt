package com.coc.suncode.jar.features.clan

import android.content.Context
import com.coc.suncode.interfaces.FeaturePlugin
import com.coc.suncode.interfaces.FeatureResult
import com.coc.suncode.jar.code.mainbase.clan.joinClan
import com.coc.suncode.jar.code.mainbase.clan.donateToClan
import com.coc.suncode.jar.code.mainbase.clan.requestReinforcements

/** Feature entry point; shared state is resolved through the parent core loader. */
class Entry : FeaturePlugin {
    override suspend fun execute(context: Context, action: String, account: Int): FeatureResult {
        val success = when (action) {
            "join" -> joinClan()
            "donate" -> donateToClan()
            "request" -> requestReinforcements()
            else -> error("Unsupported action: $action")
        }
        return if (success) FeatureResult.SUCCESS else FeatureResult.FAILED
    }
}
