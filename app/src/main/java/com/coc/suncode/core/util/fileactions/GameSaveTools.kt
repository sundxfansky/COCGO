package com.coc.suncode.core.util.fileactions

import android.os.Environment
import com.coc.suncode.core.util.basic.ServerShell
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Bounded archive operations backed by the host's private Root worker. */
object GameSaveTools {
    enum class Region(
        internal val folder: String,
        internal val packageName: String,
        internal val directories: List<String>
    ) {
        CN("sunCNGameSave", "com.tencent.tmgp.supercell.clashofclans", listOf("shared_prefs", "databases")),
        GLOBAL("sunGlobalGameSave", "com.supercell.clashofclans", listOf("shared_prefs"))
    }

    private val archiveMutex = Mutex()

    suspend fun extract(region: Region, suffix: String) = archiveMutex.withLock {
        validate(suffix)
        val root = archiveRoot(region)
        val target = "$root/$suffix"
        // Stage the copy beside the destination so a failed extraction leaves no partial archive.
        val copies = region.directories.joinToString("\n") { folder ->
            val source = "/data/data/${region.packageName}/$folder"
            """mkdir "${'$'}temporary/$folder" && cp -R "$source/." "${'$'}temporary/$folder/" || exit 19"""
        }
        val result = ServerShell.exec(
            """
            [ ! -e "$target" ] || exit 17
            mkdir -p "$root" || exit 18
            temporary=${'$'}(mktemp -d "$root/.extract.XXXXXX") || exit 18
            trap 'rm -rf "${'$'}temporary"' EXIT
            $copies
            mv -nT "${'$'}temporary" "$target" || exit 19
            [ ! -e "${'$'}temporary" ] || exit 17
            """.trimIndent()
        )
        check(result.code != 17) { "存档已存在" }
        check(result.isSuccess) { "提取失败，请检查游戏存档和存储权限" }
    }

    suspend fun delete(region: Region, suffix: String) = archiveMutex.withLock {
        validate(suffix)
        val target = "${archiveRoot(region)}/$suffix"
        val result = ServerShell.exec("""[ -d "$target" ] || exit 17; rm -rf "$target" """.trim())
        check(result.code != 17) { "存档不存在" }
        check(result.isSuccess) { "删除失败，请检查存储权限" }
    }

    private fun validate(value: String) {
        require(SavePath.isValid(value)) { "存档名只能包含文字、数字、点、横线和下划线" }
    }

    private fun archiveRoot(region: Region): String =
        "${Environment.getExternalStorageDirectory().path}/sunFiles/${region.folder}"
}
