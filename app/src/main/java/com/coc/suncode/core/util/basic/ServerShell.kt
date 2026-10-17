package com.coc.suncode.core.util.basic

import com.coc.suncode.core.data.database.GlobalVars
import java.io.IOException

/** Shell compatibility for existing scripts, executed by the host's internal root worker. */
object ServerShell {
    data class Result(val code: Int, val out: List<String>) { val isSuccess get() = code == 0 }
    suspend fun exec(command: String): Result {
        val actions = GlobalVars.serverActions ?: throw IOException("本地 Root 功能尚未就绪")
        val response = actions.sendActionSync(mapOf("actionType" to "shell_action", "command" to command), 30_000)
            ?: throw IOException("Root command failed or timed out")
        if (response.get("status")?.asString != "success") throw IOException(response.get("message")?.asString ?: "Root command failed")
        return Result(response.get("exitCode").asInt, response.get("data").asString.lines())
    }
}
