package com.arkaedan.imageconverter.conversion

import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes Windows bitmaps. Android can decode BMP but has no encoder for it.
 *
 * Opaque images are written as classic 24-bit BMPs (BITMAPINFOHEADER), which every reader supports.
 * Images with transparency are written as 32-bit BGRA with a BITMAPV4HEADER so the alpha channel
 * is declared explicitly.
 */
object BmpEncoder {
    private const val FILE_HEADER_SIZE = 14
    private const val INFO_HEADER_SIZE = 40
    private const val V4_HEADER_SIZE = 108
    private const val BI_RGB = 0
    private const val BI_BITFIELDS = 3
    private const val LCS_SRGB = 0x73524742 // 'sRGB'
    private const val PIXELS_PER_METRE_72_DPI = 2835

    fun encode(source: PixelSource, out: OutputStream) {
        val width = source.width
        val height = source.height
        val withAlpha = source.hasAlpha
        val bitsPerPixel = if (withAlpha) 32 else 24
        val bytesPerPixel = bitsPerPixel / 8
        // Rows are padded to a multiple of four bytes.
        val rowSize = (width * bytesPerPixel + 3) and 3.inv()
        val dibHeaderSize = if (withAlpha) V4_HEADER_SIZE else INFO_HEADER_SIZE
        val pixelOffset = FILE_HEADER_SIZE + dibHeaderSize
        val imageSize = rowSize.toLong() * height
        val fileSize = pixelOffset + imageSize
        if (fileSize > UInt.MAX_VALUE.toLong()) throw IOException("Image is too large for BMP")

        val header = ByteBuffer.allocate(pixelOffset).order(ByteOrder.LITTLE_ENDIAN)
        // BITMAPFILEHEADER
        header.put('B'.code.toByte()).put('M'.code.toByte())
        header.putInt(fileSize.toInt())
        header.putInt(0) // reserved
        header.putInt(pixelOffset)
        // BITMAPINFOHEADER; a positive height means rows are stored bottom-up.
        header.putInt(dibHeaderSize)
        header.putInt(width)
        header.putInt(height)
        header.putShort(1) // colour planes
        header.putShort(bitsPerPixel.toShort())
        header.putInt(if (withAlpha) BI_BITFIELDS else BI_RGB)
        header.putInt(imageSize.toInt())
        header.putInt(PIXELS_PER_METRE_72_DPI)
        header.putInt(PIXELS_PER_METRE_72_DPI)
        header.putInt(0) // palette colours
        header.putInt(0) // important colours
        if (withAlpha) {
            // BITMAPV4HEADER extension: channel masks, colour space, then unused endpoints and gamma.
            header.putInt(0x00FF0000)
            header.putInt(0x0000FF00)
            header.putInt(0x000000FF)
            header.putInt(0xFF000000.toInt())
            header.putInt(LCS_SRGB)
            repeat(9 + 3) { header.putInt(0) }
        }
        check(!header.hasRemaining())
        out.write(header.array())

        val pixels = IntArray(width)
        val row = ByteArray(rowSize)
        for (y in height - 1 downTo 0) {
            source.readRow(y, pixels)
            var i = 0
            for (x in 0 until width) {
                val color = pixels[x]
                row[i++] = color.toByte()
                row[i++] = (color shr 8).toByte()
                row[i++] = (color shr 16).toByte()
                if (withAlpha) row[i++] = (color ushr 24).toByte()
            }
            out.write(row)
        }
    }
}
