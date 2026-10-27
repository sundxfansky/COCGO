package com.coc.suncode.nativehelper

object RustTools {
    init {
        // 名字必须和 Cargo.toml 中的 name 一致
        System.loadLibrary("rust_logic")
    }

    // 声明 native 方法，名字必须和 Rust 里的对应
    external fun sayHello(input: String): String

    external fun createInMemoryDex(data: ByteArray): Int

    external fun findMultiColors(
        bitmap: android.graphics.Bitmap,
        x1: Int, y1: Int, x2: Int, y2: Int,
        mainColor: Int,
        threshold: Int,
        flatOffsets: IntArray,
        direction: Int,
        increment: Int
    ): IntArray?

    external fun findMultiColorsRaw(
        byteBuffer: java.nio.ByteBuffer,
        width: Int, height: Int,
        stride: Int,
        x1: Int, y1: Int, x2: Int, y2: Int,
        mainColor: Int,
        threshold: Int,
        flatOffsets: IntArray,
        direction: Int,
        increment: Int
    ): IntArray?

    /**
     * Block-mean template search. [means] holds 0xRRGGBB block means in row-major order.
     * Returns [x, y, score * 10000] of the template's top-left corner, or null.
     */
    external fun findBlockTemplateRaw(
        byteBuffer: java.nio.ByteBuffer,
        width: Int, height: Int,
        stride: Int,
        x1: Int, y1: Int, x2: Int, y2: Int,
        block: Int, cols: Int, rows: Int,
        means: IntArray,
        minScore: Double
    ): IntArray?
}
