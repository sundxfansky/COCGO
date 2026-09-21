package com.coc.suncode.core.util.basic

import com.coc.suncode.core.data.database.GlobalVars
import java.io.IOException

/** Shell compatibility for existing scripts, transported through the authenticated root WebSocket. */
object ServerShell {
    data class Result(val code: Int, val out: List<String>) { val isSuccess get() = code == 0 }
    suspend fun exec(command: String): Result {
        val actions = GlobalVars.serverActions ?: throw IOException("Root server is not ready")
        val response = actions.sendActionSync(mapOf("actionType" to "shell_action", "command" to command), 30_000)
            ?: throw IOException("Root command failed or timed out")
        if (response.get("status")?.asString != "success") throw IOException(response.get("message")?.asString ?: "Root command failed")
        return Result(response.get("exitCode").asInt, response.get("data").asString.lines())
    }
}
