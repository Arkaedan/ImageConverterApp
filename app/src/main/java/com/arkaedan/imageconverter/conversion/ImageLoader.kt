package com.arkaedan.imageconverter.conversion

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.graphics.createBitmap
import com.caverock.androidsvg.SVG
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/** Basic facts about a picked file, read without decoding the whole image. */
data class ImageDetails(
    val width: Int,
    val height: Int,
    val formatLabel: String?,
    val thumbnail: Bitmap,
)

/**
 * Decodes picked images. Raster formats go through [ImageDecoder], which applies EXIF orientation and
 * supports everything the platform does (JPEG, PNG, WebP, GIF, BMP, HEIF, AVIF, DNG, ...). SVGs are
 * rendered with AndroidSVG, since Android has no SVG support of its own.
 */
class ImageLoader(private val resolver: ContentResolver) {

    fun displayName(uri: Uri): String? = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        ?: uri.lastPathSegment?.substringAfterLast('/')

    fun fileSize(uri: Uri): Long? = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null }

    fun isSvg(uri: Uri, displayName: String?): Boolean {
        val type = resolver.getType(uri)
        if (type == SVG_MIME_TYPE) return true
        val name = displayName?.lowercase().orEmpty()
        if (name.endsWith(".svg") || name.endsWith(".svgz")) return true
        // Some providers report SVGs as generic files; sniff the start of the content.
        if (type == null || type == "application/octet-stream" || type == "text/xml" || type == "application/xml") {
            val head = runCatching {
                resolver.openInputStream(uri)?.use { stream ->
                    val buffer = ByteArray(1024)
                    String(buffer, 0, stream.read(buffer).coerceAtLeast(0), Charsets.UTF_8)
                }
            }.getOrNull() ?: return false
            return head.contains("<svg", ignoreCase = true)
        }
        return false
    }

    /** Reads dimensions, format and a small thumbnail whose longest side is [thumbnailSize]. */
    fun details(uri: Uri, isSvg: Boolean, thumbnailSize: Int): ImageDetails {
        if (isSvg) {
            val svg = loadSvg(uri)
            val (width, height) = svg.intrinsicSize()
            return ImageDetails(
                width = width.roundToInt().coerceAtLeast(1),
                height = height.roundToInt().coerceAtLeast(1),
                formatLabel = "SVG",
                thumbnail = renderSvg(svg, thumbnailSize),
            )
        }
        var width = 0
        var height = 0
        var mimeType: String? = null
        val thumbnail = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            width = info.size.width
            height = info.size.height
            mimeType = info.mimeType
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(info.size.width, info.size.height, thumbnailSize)
        }
        return ImageDetails(width, height, formatLabelForMimeType(mimeType), thumbnail)
    }

    /**
     * Decodes the full image. Raster images are scaled down so their longest side is at most
     * [longEdge]; SVGs are drawn with their longest side exactly [longEdge] (or their declared size
     * when [longEdge] is null).
     */
    fun decode(uri: Uri, isSvg: Boolean, longEdge: Int?): Bitmap {
        if (isSvg) {
            val svg = loadSvg(uri)
            val intrinsic = svg.intrinsicSize().let { max(it.first, it.second) }
            val target = (longEdge?.toFloat() ?: intrinsic).coerceAtMost(MAX_SVG_EDGE.toFloat())
            return renderSvg(svg, target.roundToInt().coerceAtLeast(1))
        }
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            if (longEdge != null) decoder.setTargetSize(info.size.width, info.size.height, longEdge)
        }
    }

    private fun ImageDecoder.setTargetSize(width: Int, height: Int, longEdge: Int) {
        val longest = max(width, height)
        if (longest <= longEdge) return
        val scale = longEdge.toFloat() / longest
        setTargetSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
    }

    private fun loadSvg(uri: Uri): SVG {
        val stream = resolver.openInputStream(uri) ?: throw IOException("Couldn't open $uri")
        return stream.use { SVG.getFromInputStream(it) }
    }

    /** The size the SVG declares, falling back to its viewBox and then to a default square. */
    private fun SVG.intrinsicSize(): Pair<Float, Float> {
        val width = documentWidth
        val height = documentHeight
        val viewBox = documentViewBox
        return when {
            width > 0 && height > 0 -> width to height
            viewBox != null && viewBox.width() > 0 && viewBox.height() > 0 -> {
                // Only one dimension may be declared; keep the viewBox aspect ratio.
                val aspect = viewBox.width() / viewBox.height()
                when {
                    width > 0 -> width to width / aspect
                    height > 0 -> height * aspect to height
                    else -> viewBox.width() to viewBox.height()
                }
            }
            else -> DEFAULT_SVG_EDGE.toFloat() to DEFAULT_SVG_EDGE.toFloat()
        }
    }

    private fun renderSvg(svg: SVG, longEdge: Int): Bitmap {
        val (width, height) = svg.intrinsicSize()
        // Without a viewBox, AndroidSVG draws at the declared size instead of scaling to fit.
        if (svg.documentViewBox == null) svg.setDocumentViewBox(0f, 0f, width, height)
        val scale = longEdge / max(width, height)
        val outWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val outHeight = (height * scale).roundToInt().coerceAtLeast(1)
        svg.documentWidth = outWidth.toFloat()
        svg.documentHeight = outHeight.toFloat()
        val bitmap = createBitmap(outWidth, outHeight)
        svg.renderToCanvas(Canvas(bitmap))
        return bitmap
    }

    companion object {
        const val SVG_MIME_TYPE = "image/svg+xml"
        private const val DEFAULT_SVG_EDGE = 1024
        private const val MAX_SVG_EDGE = 8192
    }
}
