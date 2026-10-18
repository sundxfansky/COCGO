package com.coc.suncode.interfaces

/** Optional host/plugin contract so the floating controls never import dynamic classes. */
interface RuntimeAccountSwitcher {
    suspend fun accountSwitchState(): AccountSwitchState
    suspend fun switchToAccount(accountNumber: Int): Boolean
}

data class AccountSwitchState(
    val previousAccount: Int? = null,
    val nextAccount: Int? = null
)
