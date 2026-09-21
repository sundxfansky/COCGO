package com.coc.suncode.loadjar

import java.security.KeyPairGenerator
import java.security.Signature
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginPackageTest {
    @Test fun legacyCipherMatchesPublishedChaCha20Vector() {
        val expected = "76b8e0ada0f13d90405d6ae55386bd28bdd219b8a08ded1aa836efcc8b770dc7" +
            "da41597c5157488d7724e03fb8d84a376a43b8f41518a11cc387b669b2ee6586"
        val result = PluginPackage.chacha20(ByteArray(64), ByteArray(32), ByteArray(12))
        assertEquals(expected, result.joinToString("") { "%02x".format(it) })
    }
    @Test fun cipherRoundTripCrossesBlockBoundaries() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it+32).toByte() }
        val original = ByteArray(133) { (it*17).toByte() }
        val encrypted = nonce + PluginPackage.chacha20(original, key.copyOf(), nonce)
        assertArrayEquals(original, PluginPackage.decrypt(encrypted, key.joinToString("") { "%02x".format(it) }))
    }
    @Test fun signatureRejectsModifiedBytesAndRenamedVersion() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val payload = "encrypted_123.jar\u0000payload".toByteArray()
        val signature = Signature.getInstance("SHA256withRSA").run { initSign(pair.private); update(payload); sign() }
        assertTrue(PluginPackage.verify(payload, signature, pair.public.encoded))
        assertFalse(PluginPackage.verify("encrypted_124.jar\u0000payload".toByteArray(), signature, pair.public.encoded))
        assertFalse(PluginPackage.verify(payload + 1.toByte(), signature, pair.public.encoded))
    }
    @Test fun versionsRejectPathTraversalAndMalformedNames() {
        assertEquals(123L, PluginPackage.version("encrypted_123.jar"))
        assertEquals(124L, PluginPackage.version("124.jar"))
        assertNull(PluginPackage.version("../123.jar"))
        assertNull(PluginPackage.version("encrypted_123.jar.tmp"))
        assertNull(PluginPackage.version("9999999999999999999999999999.jar"))
    }
}
