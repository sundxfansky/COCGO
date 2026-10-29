package com.coc.suncode.core.system.daemon

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

internal object ServerApkInstaller {
    fun install(destination: File, openAsset: () -> InputStream): Boolean {
        val assetDigest = openAsset().use { digest(it) }
        if (destination.exists() && destination.inputStream().use { digest(it) }.contentEquals(assetDigest)) {
            check(destination.setReadOnly()) { "Cannot mark the server APK read-only" }
            return false
        }

        // Replace old assets atomically, including read-only APKs from previous app versions.
        val temporaryFile = File.createTempFile("server-", ".apk", destination.parentFile)
        try {
            temporaryFile.outputStream().use { output ->
                // Open before marking read-only to satisfy Android's dynamic-code loading rules.
                check(temporaryFile.setReadOnly()) { "Cannot mark the new server APK read-only" }
                openAsset().use { input -> input.copyTo(output) }
            }
            if (!temporaryFile.renameTo(destination)) {
                // Some file systems (e.g. Windows) refuse to rename over an existing or read-only file.
                destination.setWritable(true)
                if (!destination.delete() || !temporaryFile.renameTo(destination)) {
                    throw IOException("Cannot replace the installed server APK")
                }
            }
            return true
        } finally {
            temporaryFile.delete()
        }
    }

    private fun digest(input: InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var count = input.read(buffer)
        while (count != -1) {
            digest.update(buffer, 0, count)
            count = input.read(buffer)
        }
        return digest.digest()
    }
}
