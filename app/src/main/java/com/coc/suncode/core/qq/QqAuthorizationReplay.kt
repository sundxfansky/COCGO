package com.coc.suncode.core.qq

import java.io.IOException

/** Replace both companion inputs for every account switch before enabling replay. */
internal class QqAuthorizationReplay(private val backend: QqReplayBackend) {
    suspend fun prepare(record: QqAuthorizationRecord) {
        if (record.isExpired()) throw IOException("QQ 授权已过期，请重新获取")
        val input = record.toolInput()
        backend.stopTool()
        backend.putPreference("shsj", "raw_text", input)
        backend.writeText(backend.externalInputPath, input)
        // The external file takes precedence in the companion, so a stale account must fail closed.
        if (backend.readText(backend.externalInputPath) != input) {
            throw IOException("QQ 授权同步校验失败，未继续登录")
        }
        backend.putPreference("ms", "pd_key", "1")
    }
}

/** The implementation delegates all shared companion I/O to the host Root pipe. */
internal interface QqReplayBackend {
    val externalInputPath: String
    suspend fun stopTool()
    suspend fun putPreference(name: String, key: String, value: String)
    suspend fun writeText(path: String, content: String)
    suspend fun readText(path: String): String
}
