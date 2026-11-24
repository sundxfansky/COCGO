package com.coc.suncode.core.data.village

import android.os.Environment
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.util.basic.ServerShell
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.IOException

/** Reads existing snapshots through the host's root worker, including exports from older JARs. */
internal object CocVillageExports {
    private val filename = Regex("game-(cn|global)-([1-9][0-9]{0,4})\\.json")

    data class Slot(val region: String, val account: Int) {
        init {
            require(region in setOf("cn", "global") && account in 1..10_000) { "请选择有效的游戏账号和区服" }
        }

        fun summary() = JsonObject().apply {
            addProperty("region", region)
            addProperty("account", account)
        }
    }

    private val directory get() = "${Environment.getExternalStorageDirectory().path}/sunFiles/exports"

    suspend fun list(): JsonObject {
        val quoted = "'" + directory.replace("'", "'\\''") + "'"
        // Enumerate only existing files so large account ranges do not require one root call per slot.
        val output = ServerShell.exec(
            "for f in $quoted/game-*.json; do if [ -f \"\$f\" ]; then printf '%s\\n' \"\${f##*/}\"; fi; done"
        )
        if (!output.isSuccess) throw IOException("无法读取游戏导出目录")
        val slots = output.out.mapNotNull { name ->
            val match = filename.matchEntire(name) ?: return@mapNotNull null
            val account = match.groupValues[2].toInt()
            if (account > 10_000) return@mapNotNull null
            Slot(match.groupValues[1], account)
        }.sortedWith(compareBy(Slot::account, Slot::region))
        return JsonObject().apply {
            add("exports", JsonArray().apply { slots.forEach { add(it.summary()) } })
        }
    }

    suspend fun read(slot: Slot): JsonObject? {
        val actions = GlobalVars.serverActions ?: throw IOException("本地 Root 功能尚未就绪")
        val result = actions.sendActionSync(mapOf(
            "actionType" to "file_action",
            "subAction" to "read",
            "path" to "$directory/game-${slot.region}-${slot.account}.json"
        )) ?: throw IOException("读取游戏 JSON 超时")
        if (result.get("code")?.asString == "NOT_FOUND") return null
        if (result.get("status")?.asString != "success") throw IOException("无法读取游戏 JSON")
        return CocVillageParser.parse(result.get("data").asString)
    }
}
