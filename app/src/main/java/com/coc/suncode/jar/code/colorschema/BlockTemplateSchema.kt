package com.coc.suncode.jar.code.colorschema

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.Build
import android.util.Log
import com.coc.suncode.core.util.basic.ShowMessage
import java.util.concurrent.ConcurrentHashMap

/**
 * Block-mean image template whose image is a PNG in the templates directory
 * (app/src/main/resources/com/coc/suncode/jar/templates/<imageName>.png, saved by tools/ImageMatchTool).
 *
 * The PNG is packed into the core plugin jar (and the APK as a fallback), decoded on first use and reduced to the mean RGB
 * color of every [block] x [block] cell. [x1]..[y2] is the search area on screen (inclusive).
 */
class BlockTemplateSchema(
    val x1: Int, val y1: Int, val x2: Int, val y2: Int,
    val imageName: String,
    val block: Int,
    val similarity: Double,
    val name: String? = null
) {
    /** Block means computed from the template image. */
    class Template(val cols: Int, val rows: Int, val means: IntArray)

    init {
        require(block in 1..16) { "block must be within 1..16: $block" }
        require(IMAGE_NAME.matches(imageName)) { "Invalid template image name: $imageName" }
    }

    /** Loaded template, or null when the image is missing or invalid (the error is logged once). */
    val template: Template? get() = load(imageName, block)

    /** Template size in pixels (only whole blocks are matched), 0 if the image failed to load. */
    val width: Int get() = template?.let { it.cols * block } ?: 0
    val height: Int get() = template?.let { it.rows * block } ?: 0

    companion object {
        /** Resource directory inside the plugin jar (and the APK as a fallback). */
        const val TEMPLATE_DIR = "com/coc/suncode/jar/templates"

        private val IMAGE_NAME = Regex("[A-Za-z0-9_]+")

        // Keyed by "imageName@block"; failed loads are cached as Failed so they are reported only once.
        private object Failed
        private val cache = ConcurrentHashMap<String, Any>()

        /**
         * Creates a schema that reads [imageName].png from the templates directory.
         * @param block Block edge in pixels used for mean sampling.
         * @param similarity Minimum similarity (0..1) for a match.
         */
        fun fromImage(
            x1: Int, y1: Int, x2: Int, y2: Int,
            imageName: String,
            block: Int = 5,
            similarity: Double = 0.95,
            name: String? = null
        ): BlockTemplateSchema = BlockTemplateSchema(x1, y1, x2, y2, imageName, block, similarity, name)

        /** Returns a copy of [schema] with a new search area. */
        fun rescope(schema: BlockTemplateSchema, x1: Int, y1: Int, x2: Int, y2: Int): BlockTemplateSchema {
            return BlockTemplateSchema(x1, y1, x2, y2, schema.imageName, schema.block, schema.similarity, schema.name)
        }

        private fun load(imageName: String, block: Int): Template? {
            val cached = cache.getOrPut("$imageName@$block") { decode(imageName, block) ?: Failed }
            return cached as? Template
        }

        private fun decode(imageName: String, block: Int): Template? {
            val path = "$TEMPLATE_DIR/$imageName.png"
            return try {
                // The plugin class loader resolves plugin resources from the plugin jar first.
                val stream = BlockTemplateSchema::class.java.classLoader?.getResourceAsStream(path)
                if (stream == null) {
                    ShowMessage.record("找不到模板图片：$path", Log.ERROR)
                    return null
                }
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inPremultiplied = false
                    inScaled = false
                    // Keep raw sRGB values; the tool saves images tagged as sRGB without conversion.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                    }
                }
                val bitmap = stream.use { BitmapFactory.decodeStream(it, null, options) }
                if (bitmap == null) {
                    ShowMessage.record("模板图片解码失败：$path", Log.ERROR)
                    return null
                }
                try {
                    blockMeans(bitmap, block).also {
                        if (it == null) ShowMessage.record("模板图片小于一个区块：$path (${bitmap.width}x${bitmap.height}, block=$block)", Log.ERROR)
                    }
                } finally {
                    bitmap.recycle()
                }
            } catch (e: Exception) {
                ShowMessage.record("读取模板图片失败：$path", Log.ERROR, throwable = e)
                null
            }
        }

        /**
         * Computes rounded per-block channel means as 0xRRGGBB, row-major.
         * Must stay identical to BlockTemplate.make in tools/ImageMatchTool/Sources/Matcher.swift.
         */
        private fun blockMeans(bitmap: Bitmap, block: Int): Template? {
            val cols = bitmap.width / block
            val rows = bitmap.height / block
            if (cols <= 0 || rows <= 0) return null
            val w = bitmap.width
            val pixels = IntArray(w * rows * block)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, rows * block)
            val area = block * block
            val means = IntArray(cols * rows)
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    var sr = 0; var sg = 0; var sb = 0
                    for (dy in 0 until block) {
                        val rowStart = (r * block + dy) * w + c * block
                        for (dx in 0 until block) {
                            val p = pixels[rowStart + dx]
                            sr += (p shr 16) and 0xFF
                            sg += (p shr 8) and 0xFF
                            sb += p and 0xFF
                        }
                    }
                    val mr = (sr + area / 2) / area
                    val mg = (sg + area / 2) / area
                    val mb = (sb + area / 2) / area
                    means[r * cols + c] = (mr shl 16) or (mg shl 8) or mb
                }
            }
            return Template(cols, rows, means)
        }
    }
}
