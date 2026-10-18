package com.coc.suncode.loadjar.features

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Properties
import java.util.zip.ZipInputStream

data class FeatureMetadata(val id: String, val version: Long, val api: Int)

/** Metadata is inside the signed bytes, so an artifact cannot be relabelled as another feature. */
object FeatureArchive {
    const val API = 1
    const val LIMIT = 64 * 1024 * 1024

    fun metadata(bytes: ByteArray): FeatureMetadata {
        require(bytes.size in 1..LIMIT) { "插件大小无效" }
        var metadata: FeatureMetadata? = null
        var dex = false
        var remaining = LIMIT
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "classes.dex") {
                    require(!dex) { "重复的 classes.dex" }
                    dex = true
                }
                if (entry.name == "feature.properties") {
                    require(metadata == null) { "重复的插件描述" }
                    val content = zip.readBytesLimited(4096)
                    remaining -= content.size
                    require(remaining >= 0)
                    val props = Properties().apply { load(content.inputStream()) }
                    val id = props.getProperty("id").orEmpty()
                    require(id.matches(Regex("[a-z][a-z0-9_]{0,63}")))
                    val version = props.getProperty("version").toLong()
                    val api = props.getProperty("api").toInt()
                    require(version > 0 && api == API) { "插件版本或接口不兼容" }
                    metadata = FeatureMetadata(id, version, api)
                }
                // Bound decompression even for ignored ZIP entries.
                remaining -= zip.readBytesLimited(remaining).size
            }
        }
        require(dex) { "插件缺少 classes.dex" }
        return requireNotNull(metadata) { "插件缺少 feature.properties" }
    }
}

internal fun InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size().toLong() + count <= limit) { "插件数据超出大小限制" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
