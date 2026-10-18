package com.coc.suncode.loadjar.features

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureArchiveTest {
    private fun archive(properties: String, dex: Boolean = true): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("feature.properties")); zip.write(properties.toByteArray()); zip.closeEntry()
            if (dex) { zip.putNextEntry(ZipEntry("classes.dex")); zip.write(byteArrayOf(1)); zip.closeEntry() }
        }
        return output.toByteArray()
    }

    @Test fun readsIdentityAndVersionFromTheSignedArchive() {
        assertEquals(FeatureMetadata("main_attack", 7, 1), FeatureArchive.metadata(archive("id=main_attack\nversion=7\napi=1")))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsIncompatibleCoreApi() {
        FeatureArchive.metadata(archive("id=login\nversion=1\napi=2"))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsPathTraversal() {
        FeatureArchive.metadata(archive("id=../login\nversion=1\napi=1"))
    }

    @Test(expected = IllegalArgumentException::class) fun requiresDex() {
        FeatureArchive.metadata(archive("id=login\nversion=1\napi=1", dex = false))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsOversizedMetadata() {
        FeatureArchive.metadata(archive("id=login\nversion=1\napi=1\nextra=" + "x".repeat(4096)))
    }

    @Test fun classOwnershipIncludesGeneratedClassesButNotSimilarlyNamedHelpers() {
        val spec = FeatureSpec("upgrade", "升级", "Entry", listOf("example.UpgradeKt", "example.entry."))
        assertTrue(spec.owns("example.UpgradeKt"))
        assertTrue(spec.owns("example.UpgradeKt\$Continuation"))
        assertTrue(spec.owns("example.entry.Entry"))
        assertFalse(spec.owns("example.UpgradeKtHelper"))
        assertFalse(spec.owns("example.entries.Entry"))
    }
}
