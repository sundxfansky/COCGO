package com.coc.suncode.core.system.daemon

import android.content.Context
import java.security.SecureRandom

/** A private per-install credential shared only with the detached root process. */
object RootServerAuth {
    @Volatile var token: String = ""
        private set

    @Synchronized fun initialize(context: Context): String {
        if (token.isNotEmpty()) return token
        val prefs = context.getSharedPreferences("root_server", Context.MODE_PRIVATE)
        token = prefs.getString("token", null) ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }.also {
                check(prefs.edit().putString("token", it).commit()) { "Cannot persist root credential" }
            }
        return token
    }
}
