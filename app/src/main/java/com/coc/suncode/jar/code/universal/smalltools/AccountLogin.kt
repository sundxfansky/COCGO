package com.coc.suncode.jar.code.universal.smalltools

import android.content.Context
import com.coc.suncode.interfaces.FeatureResult
import com.coc.suncode.jar.features.FeatureRuntime

enum class AccountLoginResult { STARTED, AUTHORIZING, FAILED }

/** Manual and automatic login share the same independently replaceable plugin. */
suspend fun loginConfiguredAccount(context: Context, index: Int): AccountLoginResult =
    when (FeatureRuntime.execute("login", "manual", index)) {
        FeatureResult.SUCCESS -> AccountLoginResult.STARTED
        FeatureResult.AUTHORIZING -> AccountLoginResult.AUTHORIZING
        else -> AccountLoginResult.FAILED
    }
