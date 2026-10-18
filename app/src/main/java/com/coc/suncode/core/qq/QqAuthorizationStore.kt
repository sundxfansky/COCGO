package com.coc.suncode.core.qq

import android.content.Context
import android.util.AtomicFile
import com.google.gson.JsonParser
import java.io.File

/** Shared numbered grants are host-private and excluded from ordinary/cloud configuration and backup. */
internal class QqAuthorizationStore(context: Context) {
    companion object {
        // AtomicFile requires callers to serialize reads, replacement and deletion across store instances.
        private val fileLock = Any()
    }

    private val directory = File(context.noBackupFilesDir, "qq_authorizations")

    private fun file(slot: Int): AtomicFile {
        require(slot in 1..10_000) { "授权序号须为 1 至 10000" }
        return AtomicFile(File(directory, "$slot.json"))
    }

    fun read(slot: Int): QqAuthorizationRecord? = synchronized(fileLock) {
        val target = file(slot)
        if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) return@synchronized null
        target.openRead().bufferedReader().use { QqAuthorizationRecord.fromStored(JsonParser.parseReader(it).asJsonObject) }
    }

    fun write(slot: Int, record: QqAuthorizationRecord) = synchronized(fileLock) {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建授权保存目录" }
        val target = file(slot)
        val output = target.startWrite()
        try {
            output.write(record.toJson().toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(output)
        } catch (error: Exception) {
            target.failWrite(output)
            throw error
        }
    }

    fun clear() = synchronized(fileLock) {
        check(!directory.exists() || directory.deleteRecursively()) { "无法清除 QQ 授权" }
    }
}
