package com.arkaedan.imageconverter.conversion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Paint
import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.core.graphics.createBitmap
import androidx.heifwriter.AvifWriter
import androidx.heifwriter.HeifWriter
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Encodes decoded bitmaps into each [ImageFormat]. */
class ImageEncoders(private val scratchDir: File) {

    fun encode(bitmap: Bitmap, settings: ConversionSettings, out: OutputStream) {
        val format = settings.format
        val prepared = bitmap.prepared(
            keepWideGamut = format in COLOR_MANAGED_FORMATS,
            flatten = !format.supportsTransparency,
        )
        try {
            encodePrepared(prepared, settings, out)
        } finally {
            if (prepared !== bitmap) prepared.recycle()
        }
    }

    private fun encodePrepared(bitmap: Bitmap, settings: ConversionSettings, out: OutputStream) {
        val quality = settings.quality.coerceIn(0, 100)
        when (settings.format) {
            ImageFormat.JPEG -> compress(bitmap, Bitmap.CompressFormat.JPEG, quality, out)
            ImageFormat.PNG -> compress(bitmap, Bitmap.CompressFormat.PNG, 100, out)
            ImageFormat.WEBP ->
                // For lossless WebP the quality value controls compression effort, not fidelity.
                if (settings.lossless) compress(bitmap, Bitmap.CompressFormat.WEBP_LOSSLESS, LOSSLESS_WEBP_EFFORT, out)
                else compress(bitmap, Bitmap.CompressFormat.WEBP_LOSSY, quality, out)
            ImageFormat.HEIF -> viaTempFile("heic", out) { path ->
                HeifWriter.Builder(path, bitmap.width, bitmap.height, HeifWriter.INPUT_MODE_BITMAP)
                    .setQuality(quality)
                    .setMaxImages(1)
                    .build()
                    .use { writer ->
                        writer.start()
                        writer.addBitmap(bitmap)
                        writer.stop(WRITER_TIMEOUT_MS)
                    }
            }
            ImageFormat.AVIF -> viaTempFile("avif", out) { path ->
                AvifWriter.Builder(path, bitmap.width, bitmap.height, AvifWriter.INPUT_MODE_BITMAP)
                    .setQuality(quality)
                    .setMaxImages(1)
                    .build()
                    .use { writer ->
                        writer.start()
                        writer.addBitmap(bitmap)
                        writer.stop(WRITER_TIMEOUT_MS)
                    }
            }
            ImageFormat.GIF -> GifEncoder.encode(BitmapPixelSource(bitmap), out)
            ImageFormat.BMP -> BmpEncoder.encode(BitmapPixelSource(bitmap), out)
        }
    }

    private fun compress(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int, out: OutputStream) {
        if (!bitmap.compress(format, quality, out)) throw IOException("Couldn't encode image as $format")
    }

    /** HeifWriter and AvifWriter need a seekable file, so encode to the cache and copy across. */
    private inline fun viaTempFile(extension: String, out: OutputStream, write: (path: String) -> Unit) {
        val file = File.createTempFile("convert", ".$extension", scratchDir)
        try {
            write(file.absolutePath)
            file.inputStream().use { it.copyTo(out) }
        } finally {
            file.delete()
        }
    }

    /**
     * Returns an 8-bit ARGB bitmap ready for encoding. Wide-gamut (Display P3) images keep their
     * colour space for [COLOR_MANAGED_FORMATS], whose encoders embed ICC profiles; everything else
     * is converted to sRGB.
     * When [flatten] is set, transparency is composited onto white for formats without alpha.
     */
    private fun Bitmap.prepared(keepWideGamut: Boolean, flatten: Boolean): Bitmap {
        val targetColorSpace = if (keepWideGamut && colorSpace == DISPLAY_P3) DISPLAY_P3 else SRGB
        val needsFlatten = flatten && hasAlpha()
        if (config == Bitmap.Config.ARGB_8888 && colorSpace == targetColorSpace && !needsFlatten) return this
        val result = createBitmap(width, height, Bitmap.Config.ARGB_8888, hasAlpha() && !flatten, targetColorSpace)
        Canvas(result).apply {
            if (needsFlatten) drawColor(Color.WHITE)
            drawBitmap(this@prepared, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return result
    }

    private companion object {
        const val WRITER_TIMEOUT_MS = 120_000L
        const val LOSSLESS_WEBP_EFFORT = 75
        val COLOR_MANAGED_FORMATS = setOf(ImageFormat.JPEG, ImageFormat.PNG, ImageFormat.WEBP)
        val SRGB: ColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        val DISPLAY_P3: ColorSpace = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
    }
}

/** Which formats this device can actually encode. HEIF and AVIF depend on hardware/software codecs. */
object EncoderSupport {
    private val encoderTypes: Set<String> by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder }
            .flatMap { it.supportedTypes.asList() }
            .map { it.lowercase() }
            .toSet()
    }

    fun isSupported(format: ImageFormat): Boolean = when (format) {
        ImageFormat.HEIF -> MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC in encoderTypes || MediaFormat.MIMETYPE_VIDEO_HEVC in encoderTypes
        ImageFormat.AVIF -> MediaFormat.MIMETYPE_VIDEO_AV1 in encoderTypes
        else -> true
    }
}
