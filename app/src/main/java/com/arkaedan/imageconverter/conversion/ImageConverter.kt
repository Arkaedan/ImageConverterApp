package com.arkaedan.imageconverter.conversion

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream

data class ConversionOutput(
    val uri: Uri,
    val format: ImageFormat,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
)

/** Converts one image at a time and saves the result to Pictures/Image Converter. */
class ImageConverter(context: Context) {
    private val resolver = context.contentResolver
    private val loader = ImageLoader(resolver)
    private val encoders = ImageEncoders(context.cacheDir)

    /** Blocking; call from a background dispatcher. */
    fun convert(input: Uri, displayName: String, isSvg: Boolean, settings: ConversionSettings): ConversionOutput {
        val bitmap = loader.decode(input, isSvg, settings.size.longEdge)
        try {
            val outputUri = createPendingImage(outputName(displayName, settings.format), settings.format)
            try {
                val stream = resolver.openOutputStream(outputUri) ?: throw IOException("Couldn't open output")
                val written = CountingOutputStream(stream.buffered()).use { counting ->
                    encoders.encode(bitmap, settings, counting)
                    counting.flush()
                    counting.count
                }
                resolver.update(outputUri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                return ConversionOutput(outputUri, settings.format, written, bitmap.width, bitmap.height)
            } catch (t: Throwable) {
                runCatching { resolver.delete(outputUri, null, null) }
                throw t
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun createPendingImage(name: String, format: ImageFormat): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, OUTPUT_RELATIVE_PATH)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return resolver.insert(collection, values) ?: throw IOException("Couldn't create output file")
    }

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }

    companion object {
        const val OUTPUT_FOLDER = "Image Converter"
        val OUTPUT_RELATIVE_PATH = "${Environment.DIRECTORY_PICTURES}/$OUTPUT_FOLDER"

        /** "holiday.photo.HEIC" -> "holiday.photo.png". MediaStore adds " (1)" etc. on collisions. */
        fun outputName(inputName: String, format: ImageFormat): String {
            val base = inputName.substringBeforeLast('.', inputName).ifBlank { "image" }
            return "$base.${format.extension}"
        }
    }
}
