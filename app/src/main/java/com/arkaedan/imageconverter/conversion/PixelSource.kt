package com.arkaedan.imageconverter.conversion

import android.graphics.Bitmap

/**
 * Supplies non-premultiplied ARGB pixels one row at a time, so the hand-written encoders never need
 * a second full-size copy of a (potentially 50+ megapixel) image in memory.
 */
interface PixelSource {
    val width: Int
    val height: Int
    val hasAlpha: Boolean

    /** Copies row [y] into [dest], which must hold at least [width] pixels. */
    fun readRow(y: Int, dest: IntArray)
}

class BitmapPixelSource(private val bitmap: Bitmap) : PixelSource {
    override val width get() = bitmap.width
    override val height get() = bitmap.height
    override val hasAlpha get() = bitmap.hasAlpha()

    override fun readRow(y: Int, dest: IntArray) {
        bitmap.getPixels(dest, 0, width, 0, y, width, 1)
    }
}

class ArrayPixelSource(
    override val width: Int,
    override val height: Int,
    private val pixels: IntArray,
    override val hasAlpha: Boolean = pixels.any { it ushr 24 != 0xFF },
) : PixelSource {
    init {
        require(pixels.size == width * height) { "Expected ${width * height} pixels, got ${pixels.size}" }
    }

    override fun readRow(y: Int, dest: IntArray) {
        pixels.copyInto(dest, 0, y * width, (y + 1) * width)
    }
}
