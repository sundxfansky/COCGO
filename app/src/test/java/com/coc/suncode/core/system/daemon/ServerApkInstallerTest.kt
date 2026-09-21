package com.coc.suncode.core.system.daemon

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ServerApkInstallerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun installsMissingServer() {
        val destination = temporaryFolder.root.resolve("server.apk")
        val asset = "new server".toByteArray()

        assertTrue(ServerApkInstaller.install(destination) { asset.inputStream() })
        assertArrayEquals(asset, destination.readBytes())
    }

    @Test
    fun replacesReadOnlyServerWithUpdatedAsset() {
        val destination = temporaryFolder.newFile("server.apk")
        destination.writeText("old server")
        assertTrue(destination.setReadOnly())
        val asset = "new server".toByteArray()

        assertTrue(ServerApkInstaller.install(destination) { asset.inputStream() })
        assertArrayEquals(asset, destination.readBytes())
        assertEquals(listOf("server.apk"), temporaryFolder.root.list()?.toList())
    }

    @Test
    fun reusesIdenticalAssetWithoutReplacingFile() {
        val destination = temporaryFolder.newFile("server.apk")
        val asset = "same server".toByteArray()
        destination.writeBytes(asset)
        var assetOpens = 0

        assertFalse(ServerApkInstaller.install(destination) {
            assetOpens++
            asset.inputStream()
        })

        assertEquals(1, assetOpens)
        assertArrayEquals(asset, destination.readBytes())
    }

    @Test
    fun failedCopyPreservesInstalledServerAndRemovesTemporaryFile() {
        val destination = temporaryFolder.newFile("server.apk")
        destination.writeText("old server")
        var assetOpens = 0

        val result = runCatching {
            ServerApkInstaller.install(destination) {
                if (++assetOpens == 2) throw IOException("Asset read failed")
                "new server".byteInputStream()
            }
        }

        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals("old server", destination.readText())
        assertEquals(listOf("server.apk"), temporaryFolder.root.list()?.toList())
    }
}
