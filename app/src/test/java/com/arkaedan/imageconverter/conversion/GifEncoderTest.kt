package com.arkaedan.imageconverter.conversion

import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GifEncoderTest {

    @Test
    fun `images with few colours round-trip exactly`() {
        val colors = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt())
        val pixels = IntArray(37 * 23) { colors[(it * 7 + it / 37) % colors.size] }
        val decoded = decode(encode(ArrayPixelSource(37, 23, pixels)))
        assertEquals(37, decoded.width)
        assertEquals(23, decoded.height)
        assertEquals(-1, decoded.transparentIndex)
        assertArrayEquals(pixels, decoded.argb())
    }

    @Test
    fun `noisy image with 255 colours exercises table resets and still round-trips`() {
        val random = Random(42)
        val palette = IntArray(255) { 0xFF000000.toInt() or random.nextInt(0x1000000) }
        val pixels = IntArray(400 * 300) { palette[random.nextInt(palette.size)] }
        val decoded = decode(encode(ArrayPixelSource(400, 300, pixels)))
        assertArrayEquals(pixels, decoded.argb())
    }

    @Test
    fun `transparent pixels use the reserved transparent index`() {
        val opaque = 0xFF336699.toInt()
        val pixels = IntArray(16 * 16) { if (it % 3 == 0) 0x10FFFFFF else opaque }
        val decoded = decode(encode(ArrayPixelSource(16, 16, pixels)))
        assertEquals(0, decoded.transparentIndex)
        val expected = IntArray(pixels.size) { if (pixels[it] ushr 24 < 0x80) 0 else opaque }
        assertArrayEquals(expected, decoded.argb())
    }

    @Test
    fun `photographic gradients are quantized to a close palette`() {
        val width = 256
        val height = 192
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            0xFF000000.toInt() or (x shl 16) or ((y * 255 / height) shl 8) or ((x + y) / 2)
        }
        val decoded = decode(encode(ArrayPixelSource(width, height, pixels)))
        assertTrue(decoded.paletteSize <= 256)
        val result = decoded.argb()
        var totalError = 0L
        for (i in pixels.indices) {
            for (shift in intArrayOf(0, 8, 16)) {
                totalError += abs((pixels[i] shr shift and 0xFF) - (result[i] shr shift and 0xFF))
            }
        }
        val meanError = totalError.toDouble() / (pixels.size * 3)
        assertTrue("mean channel error was $meanError", meanError < 10)
    }

    @Test
    fun `single pixel image encodes`() {
        val decoded = decode(encode(ArrayPixelSource(1, 1, intArrayOf(0xFF123456.toInt()))))
        assertArrayEquals(intArrayOf(0xFF123456.toInt()), decoded.argb())
    }

    private fun encode(source: PixelSource): ByteArray =
        ByteArrayOutputStream().also { GifEncoder.encode(source, it) }.toByteArray()

    private class DecodedGif(
        val width: Int,
        val height: Int,
        val palette: IntArray,
        val paletteSize: Int,
        val transparentIndex: Int,
        val indices: IntArray,
    ) {
        fun argb() = IntArray(indices.size) { i ->
            val index = indices[i]
            if (index == transparentIndex) 0 else 0xFF000000.toInt() or palette[index]
        }
    }

    /** Minimal GIF89a reader for a single, non-interlaced frame with a global colour table. */
    private fun decode(bytes: ByteArray): DecodedGif {
        var pos = 0
        fun u8() = bytes[pos++].toInt() and 0xFF
        fun u16() = u8() or (u8() shl 8)

        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
        pos = 6
        val width = u16()
        val height = u16()
        val packed = u8()
        assertTrue("global colour table expected", packed and 0x80 != 0)
        val tableSize = 2 shl (packed and 0x07)
        pos += 2
        val palette = IntArray(tableSize) { (u8() shl 16) or (u8() shl 8) or u8() }
        var transparentIndex = -1
        while (bytes[pos].toInt() == 0x21) {
            pos++
            val label = u8()
            val size = u8()
            if (label == 0xF9 && size == 4) {
                val flags = u8()
                pos += 2
                val index = u8()
                if (flags and 1 != 0) transparentIndex = index
                assertEquals(0, u8())
            } else {
                pos += size
                while (true) {
                    val block = u8()
                    if (block == 0) break
                    pos += block
                }
            }
        }
        assertEquals(0x2C, u8())
        pos += 4
        assertEquals(width, u16())
        assertEquals(height, u16())
        assertEquals(0, u8())
        val minCodeSize = u8()
        val data = ByteArrayOutputStream()
        while (true) {
            val block = u8()
            if (block == 0) break
            data.write(bytes, pos, block)
            pos += block
        }
        assertEquals(0x3B, u8())
        assertEquals(bytes.size, pos)

        val indices = lzwDecode(data.toByteArray(), minCodeSize, width * height)
        val used = indices.maxOrNull()?.plus(1) ?: 0
        return DecodedGif(width, height, palette, used, transparentIndex, indices)
    }

    /** Textbook GIF LZW decoder, independent of the encoder's implementation. */
    private fun lzwDecode(data: ByteArray, minCodeSize: Int, pixelCount: Int): IntArray {
        val clear = 1 shl minCodeSize
        val end = clear + 1
        val output = IntArray(pixelCount)
        var outPos = 0
        val table = ArrayList<IntArray>()
        fun reset() {
            table.clear()
            for (i in 0 until clear) table += intArrayOf(i)
            table += IntArray(0)
            table += IntArray(0)
        }
        reset()
        var codeSize = minCodeSize + 1
        var bitPos = 0L
        var previous: IntArray? = null
        val totalBits = data.size * 8L
        while (bitPos + codeSize <= totalBits) {
            var code = 0
            for (i in 0 until codeSize) {
                val bit = (data[((bitPos + i) / 8).toInt()].toInt() shr ((bitPos + i) % 8).toInt()) and 1
                code = code or (bit shl i)
            }
            bitPos += codeSize
            if (code == clear) {
                reset()
                codeSize = minCodeSize + 1
                previous = null
                continue
            }
            if (code == end) break
            val entry = when {
                code < table.size -> table[code]
                previous != null && code == table.size -> previous + previous[0]
                else -> throw AssertionError("Invalid LZW code $code (table size ${table.size})")
            }
            entry.copyInto(output, outPos)
            outPos += entry.size
            if (previous != null && table.size < 4096) table += previous + entry[0]
            if (table.size == 1 shl codeSize && codeSize < 12) codeSize++
            previous = entry
        }
        assertEquals("decoded pixel count", pixelCount, outPos)
        return output
    }
}
