package com.arkaedan.imageconverter.conversion

import java.io.IOException
import java.io.OutputStream

/**
 * Writes still GIF89a images. Android can decode GIF but has no encoder for it.
 *
 * Images with 256 or fewer distinct colours are written losslessly. Anything else is reduced to a
 * 256-colour palette using median cut and Floyd–Steinberg dithering. Pixels that are more than half
 * transparent become fully transparent, since GIF only supports 1-bit transparency.
 *
 * Pixels are read row by row, so memory use stays small regardless of image size.
 */
object GifEncoder {
    private const val MAX_DIMENSION = 0xFFFF
    private const val ALPHA_THRESHOLD = 0x80

    fun encode(source: PixelSource, out: OutputStream) {
        val width = source.width
        val height = source.height
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw IOException("GIF images can be at most $MAX_DIMENSION pixels wide or tall")
        }

        val palette = Palette.build(source)
        writeHeader(out, width, height, palette)
        val lzw = LzwEncoder(out, minCodeSize = 8)
        lzw.start()
        PaletteMapper(palette, width).mapRows(source) { index -> lzw.write(index) }
        lzw.finish()
        out.write(0x00) // end of image data sub-blocks
        out.write(0x3B) // trailer
    }

    private fun writeHeader(out: OutputStream, width: Int, height: Int, palette: Palette) {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        // Logical screen descriptor with a 256-entry global colour table (8 bits of colour resolution).
        out.writeShort(width)
        out.writeShort(height)
        out.write(0xF7)
        out.write(0) // background colour index
        out.write(0) // pixel aspect ratio
        for (i in 0 until 256) {
            val color = if (i < palette.size) palette.colors[i] else 0
            out.write(color shr 16 and 0xFF)
            out.write(color shr 8 and 0xFF)
            out.write(color and 0xFF)
        }
        if (palette.transparentIndex >= 0) {
            // Graphic control extension declaring the transparent palette entry.
            out.write(byteArrayOf(0x21, 0xF9.toByte(), 0x04, 0x01, 0, 0, palette.transparentIndex.toByte(), 0))
        }
        // Image descriptor covering the whole canvas, no local colour table, not interlaced.
        out.write(0x2C)
        out.writeShort(0)
        out.writeShort(0)
        out.writeShort(width)
        out.writeShort(height)
        out.write(0)
    }

    private fun OutputStream.writeShort(value: Int) {
        write(value and 0xFF)
        write(value shr 8 and 0xFF)
    }

    /** True when the pixel should be written as the transparent palette entry. */
    internal fun isTransparent(argb: Int) = argb ushr 24 < ALPHA_THRESHOLD
}

/**
 * A palette of up to 256 RGB colours. When the image has transparent pixels, entry 0 is reserved
 * for them and [transparentIndex] is 0; otherwise it is -1.
 */
internal class Palette(
    val colors: IntArray,
    val size: Int,
    val transparentIndex: Int,
    /** Set when every opaque source colour appears exactly in the palette, so no dithering is needed. */
    val exactColors: ExactColorSet?,
) {
    val isExact get() = exactColors != null

    companion object {
        private const val BITS = 5
        private const val BUCKETS = 1 shl (3 * BITS)

        fun build(source: PixelSource): Palette {
            val width = source.width
            val row = IntArray(width)
            val exact = ExactColorSet(capacity = 256)
            var exactOverflowed = false
            var hasTransparent = false
            // Histogram over 5-bit-per-channel buckets, keeping full-precision sums so each palette
            // entry is the true average of the pixels it represents.
            val counts = LongArray(BUCKETS)
            val sumR = LongArray(BUCKETS)
            val sumG = LongArray(BUCKETS)
            val sumB = LongArray(BUCKETS)
            for (y in 0 until source.height) {
                source.readRow(y, row)
                for (x in 0 until width) {
                    val argb = row[x]
                    if (GifEncoder.isTransparent(argb)) {
                        hasTransparent = true
                        continue
                    }
                    val r = argb shr 16 and 0xFF
                    val g = argb shr 8 and 0xFF
                    val b = argb and 0xFF
                    if (!exactOverflowed && !exact.add(argb and 0xFFFFFF)) exactOverflowed = true
                    val key = bucketKey(r, g, b)
                    counts[key]++
                    sumR[key] += r.toLong()
                    sumG[key] += g.toLong()
                    sumB[key] += b.toLong()
                }
            }

            val offset = if (hasTransparent) 1 else 0
            val maxColors = 256 - offset
            val colors = IntArray(256)
            if (!exactOverflowed && exact.size <= maxColors) {
                exact.values().copyInto(colors, offset)
                return Palette(colors, exact.size + offset, if (hasTransparent) 0 else -1, exact)
            }

            val quantized = MedianCut(counts, sumR, sumG, sumB).quantize(maxColors)
            quantized.copyInto(colors, offset)
            return Palette(colors, quantized.size + offset, if (hasTransparent) 0 else -1, exactColors = null)
        }

        internal fun bucketKey(r: Int, g: Int, b: Int) =
            (r shr (8 - BITS) shl (2 * BITS)) or (g shr (8 - BITS) shl BITS) or (b shr (8 - BITS))

        internal fun bucketChannel(key: Int, channel: Int) = key shr ((2 - channel) * BITS) and ((1 shl BITS) - 1)
    }
}

/**
 * Small open-addressing set of RGB values, in insertion order, that gives up once it would hold more
 * than [capacity] entries.
 */
internal class ExactColorSet(private val capacity: Int) {
    private val slots = IntArray(capacity * 4) { EMPTY }
    private val positions = IntArray(capacity * 4)
    private val mask = slots.size - 1
    private val ordered = IntArray(capacity)
    var size = 0
        private set

    /** Adds [rgb]; returns false if the set is already full and [rgb] is new. */
    fun add(rgb: Int): Boolean {
        val slot = find(rgb)
        if (slots[slot] == rgb) return true
        if (size == capacity) return false
        slots[slot] = rgb
        positions[slot] = size
        ordered[size++] = rgb
        return true
    }

    /** Insertion position of [rgb], which must be in the set. */
    fun indexOf(rgb: Int): Int {
        val slot = find(rgb)
        check(slots[slot] == rgb) { "Colour not in palette" }
        return positions[slot]
    }

    fun values(): IntArray = ordered.copyOf(size)

    private fun find(rgb: Int): Int {
        var slot = (rgb * -0x61c88647 ushr 22) and mask
        while (slots[slot] != rgb && slots[slot] != EMPTY) slot = (slot + 1) and mask
        return slot
    }

    private companion object {
        const val EMPTY = -1
    }
}

/** Median-cut colour quantization over a 5-bit-per-channel histogram. */
private class MedianCut(
    private val counts: LongArray,
    private val sumR: LongArray,
    private val sumG: LongArray,
    private val sumB: LongArray,
) {
    private val keys: IntArray = counts.indices.filter { counts[it] > 0 }.toIntArray()

    private inner class Box(val start: Int, val end: Int) {
        val population: Long
        val min = IntArray(3) { Int.MAX_VALUE }
        val max = IntArray(3) { Int.MIN_VALUE }

        init {
            var total = 0L
            for (i in start until end) {
                val key = keys[i]
                total += counts[key]
                for (c in 0..2) {
                    val v = Palette.bucketChannel(key, c)
                    if (v < min[c]) min[c] = v
                    if (v > max[c]) max[c] = v
                }
            }
            population = total
        }

        val canSplit get() = end - start > 1
        val longestChannel get() = (0..2).maxBy { max[it] - min[it] }
        val volume get() = (0..2).fold(1L) { acc, c -> acc * (max[c] - min[c] + 1) }

        fun average(): Int {
            var r = 0L
            var g = 0L
            var b = 0L
            for (i in start until end) {
                val key = keys[i]
                r += sumR[key]
                g += sumG[key]
                b += sumB[key]
            }
            return ((r / population).toInt() shl 16) or ((g / population).toInt() shl 8) or (b / population).toInt()
        }

        /** Splits at the population-weighted median of the longest channel. */
        fun split(): Pair<Box, Box> {
            val channel = longestChannel
            val shift = (2 - channel) * 5
            // Sort the keys in this box by the chosen channel using a packed primitive sort.
            val packed = LongArray(end - start) { i ->
                val key = keys[start + i]
                ((key shr shift and 0x1F).toLong() shl 32) or key.toLong()
            }
            packed.sort()
            for (i in packed.indices) keys[start + i] = packed[i].toInt()

            val half = population / 2
            var running = 0L
            var splitAt = end - 1
            for (i in start until end - 1) {
                running += counts[keys[i]]
                if (running >= half) {
                    splitAt = i + 1
                    break
                }
            }
            return Box(start, splitAt) to Box(splitAt, end)
        }
    }

    fun quantize(maxColors: Int): IntArray {
        if (keys.isEmpty()) return IntArray(0)
        val boxes = mutableListOf(Box(0, keys.size))
        // Split by population first, then favour large, sparse boxes so rare but distinct colours
        // (skies, highlights) still get palette entries.
        val populationPhase = maxColors * 3 / 4
        while (boxes.size < maxColors) {
            val byPopulation = boxes.size < populationPhase
            val target = boxes.filter { it.canSplit }.maxByOrNull {
                if (byPopulation) it.population.toDouble() else it.population.toDouble() * it.volume
            } ?: break
            boxes.remove(target)
            val (a, b) = target.split()
            boxes += a
            boxes += b
        }
        return IntArray(boxes.size) { boxes[it].average() }
    }
}

/** Maps pixels to palette indices, dithering when the palette is an approximation. */
private class PaletteMapper(private val palette: Palette, private val width: Int) {
    private val firstColor = if (palette.transparentIndex >= 0) 1 else 0
    private val reds = IntArray(palette.size) { palette.colors[it] shr 16 and 0xFF }
    private val greens = IntArray(palette.size) { palette.colors[it] shr 8 and 0xFF }
    private val blues = IntArray(palette.size) { palette.colors[it] and 0xFF }

    /** Nearest-colour cache keyed by 6 bits per channel; stores index + 1 so 0 means "unknown". */
    private val cache = IntArray(1 shl 18)

    fun mapRows(source: PixelSource, emit: (Int) -> Unit) {
        val row = IntArray(width)
        // Floyd–Steinberg error buffers (x16), padded by one pixel on each side.
        var current = IntArray((width + 2) * 3)
        var next = IntArray((width + 2) * 3)
        for (y in 0 until source.height) {
            source.readRow(y, row)
            for (x in 0 until width) {
                val argb = row[x]
                if (GifEncoder.isTransparent(argb)) {
                    emit(palette.transparentIndex)
                    continue
                }
                val exact = palette.exactColors
                if (exact != null) {
                    emit(firstColor + exact.indexOf(argb and 0xFFFFFF))
                    continue
                }
                val e = (x + 1) * 3
                val r = ((argb shr 16 and 0xFF) + current[e] / 16).coerceIn(0, 255)
                val g = ((argb shr 8 and 0xFF) + current[e + 1] / 16).coerceIn(0, 255)
                val b = ((argb and 0xFF) + current[e + 2] / 16).coerceIn(0, 255)
                val index = nearest(r, g, b)
                emit(index)
                diffuse(current, next, e, r - reds[index], g - greens[index], b - blues[index])
            }
            val swap = current
            current = next
            next = swap
            next.fill(0)
        }
    }

    private fun diffuse(current: IntArray, next: IntArray, e: Int, dr: Int, dg: Int, db: Int) {
        diffuseChannel(current, next, e, dr)
        diffuseChannel(current, next, e + 1, dg)
        diffuseChannel(current, next, e + 2, db)
    }

    private fun diffuseChannel(current: IntArray, next: IntArray, i: Int, error: Int) {
        current[i + 3] += error * 7
        next[i - 3] += error * 3
        next[i] += error * 5
        next[i + 3] += error
    }

    private fun nearest(r: Int, g: Int, b: Int): Int {
        val key = (r shr 2 shl 12) or (g shr 2 shl 6) or (b shr 2)
        val cached = cache[key]
        if (cached != 0) return cached - 1
        var best = firstColor
        var bestDistance = Int.MAX_VALUE
        for (i in firstColor until palette.size) {
            val dr = r - reds[i]
            val dg = g - greens[i]
            val db = b - blues[i]
            // Weighted towards green, which the eye is most sensitive to.
            val distance = 2 * dr * dr + 4 * dg * dg + 3 * db * db
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        cache[key] = best + 1
        return best
    }
}

/**
 * GIF-flavoured variable-length LZW (codes of 9–12 bits for 8-bit pixels), written as a sequence
 * of data sub-blocks of at most 255 bytes.
 */
internal class LzwEncoder(private val out: OutputStream, private val minCodeSize: Int) {
    private val clearCode = 1 shl minCodeSize
    private val endCode = clearCode + 1
    private var codeSize = minCodeSize + 1
    private var nextCode = endCode + 1
    private var resetCodeSizeAfterEmit = false
    private var prefix = -1

    // Open-addressing table from (prefix code, next index) to the code for that string.
    private val keys = IntArray(TABLE_SIZE) { EMPTY }
    private val values = IntArray(TABLE_SIZE)

    private var bitBuffer = 0
    private var bitCount = 0
    private val block = ByteArray(255)
    private var blockLength = 0

    fun start() {
        out.write(minCodeSize)
        emit(clearCode)
    }

    fun write(index: Int) {
        if (prefix < 0) {
            prefix = index
            return
        }
        val key = (prefix shl 8) or index
        var slot = (key * -0x61c88647 ushr 19) and TABLE_MASK
        while (true) {
            val existing = keys[slot]
            if (existing == key) {
                prefix = values[slot]
                return
            }
            if (existing == EMPTY) break
            slot = (slot + 1) and TABLE_MASK
        }
        emit(prefix)
        if (nextCode < MAX_CODES) {
            keys[slot] = key
            values[slot] = nextCode++
        } else {
            keys.fill(EMPTY)
            nextCode = endCode + 1
            resetCodeSizeAfterEmit = true
            emit(clearCode)
        }
        prefix = index
    }

    fun finish() {
        if (prefix >= 0) emit(prefix)
        emit(endCode)
        if (bitCount > 0) writeByte(bitBuffer and 0xFF)
        flushBlock()
    }

    private fun emit(code: Int) {
        bitBuffer = bitBuffer or (code shl bitCount)
        bitCount += codeSize
        while (bitCount >= 8) {
            writeByte(bitBuffer and 0xFF)
            bitBuffer = bitBuffer ushr 8
            bitCount -= 8
        }
        if (resetCodeSizeAfterEmit) {
            codeSize = minCodeSize + 1
            resetCodeSizeAfterEmit = false
        } else if (nextCode > (1 shl codeSize) - 1 && codeSize < MAX_CODE_SIZE) {
            codeSize++
        }
    }

    private fun writeByte(value: Int) {
        block[blockLength++] = value.toByte()
        if (blockLength == block.size) flushBlock()
    }

    private fun flushBlock() {
        if (blockLength == 0) return
        out.write(blockLength)
        out.write(block, 0, blockLength)
        blockLength = 0
    }

    private companion object {
        const val MAX_CODE_SIZE = 12
        const val MAX_CODES = 1 shl MAX_CODE_SIZE
        const val TABLE_SIZE = 8192
        const val TABLE_MASK = TABLE_SIZE - 1
        const val EMPTY = -1
    }
}
