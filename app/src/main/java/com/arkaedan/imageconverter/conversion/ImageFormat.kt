package com.arkaedan.imageconverter.conversion

/** An output format the app can write. */
enum class ImageFormat(
    val label: String,
    val extension: String,
    val mimeType: String,
    val isLossy: Boolean,
    val supportsTransparency: Boolean,
) {
    JPEG("JPEG", "jpg", "image/jpeg", isLossy = true, supportsTransparency = false),
    PNG("PNG", "png", "image/png", isLossy = false, supportsTransparency = true),
    WEBP("WebP", "webp", "image/webp", isLossy = true, supportsTransparency = true),
    HEIF("HEIF", "heic", "image/heic", isLossy = true, supportsTransparency = false),
    AVIF("AVIF", "avif", "image/avif", isLossy = true, supportsTransparency = false),
    GIF("GIF", "gif", "image/gif", isLossy = false, supportsTransparency = true),
    BMP("BMP", "bmp", "image/bmp", isLossy = false, supportsTransparency = true),
}

/** Longest-edge limits offered for the output. `null` keeps the source size. */
enum class OutputSize(val longEdge: Int?) {
    ORIGINAL(null),
    PX_4096(4096),
    PX_2048(2048),
    PX_1024(1024),
}

data class ConversionSettings(
    val format: ImageFormat = ImageFormat.JPEG,
    val quality: Int = 90,
    /** Only used by WebP, which supports both lossy and lossless encoding. */
    val lossless: Boolean = false,
    val size: OutputSize = OutputSize.ORIGINAL,
) {
    val usesQuality: Boolean
        get() = format.isLossy && !(format == ImageFormat.WEBP && lossless)
}

/** Short, human-friendly name for an input MIME type, e.g. "image/svg+xml" -> "SVG". */
fun formatLabelForMimeType(mimeType: String?): String? {
    val type = mimeType?.lowercase() ?: return null
    return when (type) {
        "image/jpeg", "image/jpg" -> "JPEG"
        "image/png" -> "PNG"
        "image/webp" -> "WebP"
        "image/heif", "image/heic", "image/heif-sequence", "image/heic-sequence" -> "HEIF"
        "image/avif" -> "AVIF"
        "image/gif" -> "GIF"
        "image/bmp", "image/x-ms-bmp", "image/x-bmp" -> "BMP"
        "image/svg+xml" -> "SVG"
        "image/x-adobe-dng" -> "DNG"
        "image/x-icon", "image/vnd.microsoft.icon" -> "ICO"
        else -> type.substringAfter('/').substringBefore('+').uppercase().takeIf { it.isNotBlank() }
    }
}
