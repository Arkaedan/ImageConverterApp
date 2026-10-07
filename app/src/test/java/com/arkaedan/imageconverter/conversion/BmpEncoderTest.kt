package com.arkaedan.imageconverter.conversion

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class BmpEncoderTest {

    @Test
    fun `opaque images are 24-bit bottom-up with padded rows`() {
        // 3x2: top row red, green, blue; bottom row white, black, grey.
        val pixels = intArrayOf(
            0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(),
            0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF808080.toInt(),
        )
        val bytes = encode(ArrayPixelSource(3, 2, pixels))
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val rowSize = 12 // 3 pixels * 3 bytes = 9, padded to 12
        assertEquals('B'.code, bytes[0].toInt())
        assertEquals('M'.code, bytes[1].toInt())
        assertEquals(bytes.size, header.getInt(2))
        assertEquals(54, header.getInt(10))
        assertEquals(40, header.getInt(14))
        assertEquals(3, header.getInt(18))
        assertEquals(2, header.getInt(22))
        assertEquals(24, header.getShort(28).toInt())
        assertEquals(0, header.getInt(30))
        assertEquals(54 + rowSize * 2, bytes.size)
        // The first stored row is the bottom row, in BGR order.
        assertEquals(listOf(0xFF, 0xFF, 0xFF, 0x00, 0x00, 0x00, 0x80, 0x80, 0x80), bytes.slice(54, 9))
        assertEquals(listOf(0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0x00), bytes.slice(54 + rowSize, 9))
    }

    @Test
    fun `transparent images are 32-bit BGRA with a V4 header`() {
        val bytes = encode(ArrayPixelSource(1, 1, intArrayOf(0x80112233.toInt())))
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(14 + 108, header.getInt(10))
        assertEquals(108, header.getInt(14))
        assertEquals(32, header.getShort(28).toInt())
        assertEquals(3, header.getInt(30)) // BI_BITFIELDS
        assertEquals(0xFF000000.toInt(), header.getInt(66)) // alpha mask
        assertEquals(listOf(0x33, 0x22, 0x11, 0x80), bytes.slice(122, 4))
    }

    private fun encode(source: PixelSource) = ByteArrayOutputStream().also { BmpEncoder.encode(source, it) }.toByteArray()

    private fun ByteArray.slice(from: Int, count: Int) = (from until from + count).map { this[it].toInt() and 0xFF }
}
