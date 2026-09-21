package com.coc.suncode.loadjar

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.zip.ZipInputStream
import java.io.ByteArrayInputStream

/** Legacy ChaCha20 packaging is confidentiality only; downloaded artifacts require an RSA signature. */
object PluginPackage {
    fun version(name: String): Long? = Regex("(?:encrypted_)?([0-9]+)\\.jar").matchEntire(name)
        ?.groupValues?.get(1)?.toLongOrNull()

    fun verify(bytes: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean = runCatching {
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKey))
        Signature.getInstance("SHA256withRSA").run { initVerify(key); update(bytes); verify(signature) }
    }.getOrDefault(false)

    fun requireDexJar(bytes: ByteArray) {
        require(bytes.size <= 64 * 1024 * 1024) { "Plugin is too large" }
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "classes.dex") return
            }
        }
        error("Plugin does not contain classes.dex")
    }

    fun decrypt(bytes: ByteArray, keyHex: String): ByteArray {
        require(bytes.size > 12 && keyHex.length == 64) { "Invalid encrypted plugin" }
        val key = keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return chacha20(bytes.copyOfRange(12, bytes.size), key, bytes.copyOfRange(0, 12))
    }

    fun chacha20(input: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 12)
        fun word(bytes: ByteArray, offset: Int): Int = (0..3).fold(0) { value, i -> value or ((bytes[offset+i].toInt() and 255) shl (8*i)) }
        val state = intArrayOf(0x61707865, 0x3320646e, 0x79622d32, 0x6b206574) +
            IntArray(8) { word(key, it*4) } + intArrayOf(0) + IntArray(3) { word(nonce, it*4) }
        val result = ByteArray(input.size)
        var offset = 0
        while (offset < input.size) {
            val block = state.copyOf()
            fun quarter(a: Int, b: Int, c: Int, d: Int) {
                block[a] += block[b]; block[d] = Integer.rotateLeft(block[d] xor block[a],16)
                block[c] += block[d]; block[b] = Integer.rotateLeft(block[b] xor block[c],12)
                block[a] += block[b]; block[d] = Integer.rotateLeft(block[d] xor block[a],8)
                block[c] += block[d]; block[b] = Integer.rotateLeft(block[b] xor block[c],7)
            }
            repeat(10) {
                quarter(0,4,8,12);quarter(1,5,9,13);quarter(2,6,10,14);quarter(3,7,11,15)
                quarter(0,5,10,15);quarter(1,6,11,12);quarter(2,7,8,13);quarter(3,4,9,14)
            }
            for (i in 0 until minOf(64, input.size-offset)) {
                val value = block[i/4] + state[i/4]
                result[offset+i] = (input[offset+i].toInt() xor (value ushr (8*(i%4)))).toByte()
            }
            state[12]++; offset += 64
        }
        key.fill(0)
        return result
    }
}
