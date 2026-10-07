package com.arkaedan.imageconverter.conversion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageFormatTest {

    @Test
    fun `mime types map to friendly labels`() {
        assertEquals("JPEG", formatLabelForMimeType("image/jpeg"))
        assertEquals("HEIF", formatLabelForMimeType("image/heic"))
        assertEquals("BMP", formatLabelForMimeType("image/x-ms-bmp"))
        assertEquals("SVG", formatLabelForMimeType("image/svg+xml"))
        assertEquals("TIFF", formatLabelForMimeType("image/tiff"))
        assertNull(formatLabelForMimeType(null))
    }

    @Test
    fun `output names replace only the last extension`() {
        assertEquals("holiday.photo.png", ImageConverter.outputName("holiday.photo.HEIC", ImageFormat.PNG))
        assertEquals("scan.avif", ImageConverter.outputName("scan", ImageFormat.AVIF))
        assertEquals("image.jpg", ImageConverter.outputName(".svg", ImageFormat.JPEG))
    }

    @Test
    fun `quality only applies to lossy encodings`() {
        assertTrue(ConversionSettings(format = ImageFormat.JPEG).usesQuality)
        assertTrue(ConversionSettings(format = ImageFormat.WEBP).usesQuality)
        assertFalse(ConversionSettings(format = ImageFormat.WEBP, lossless = true).usesQuality)
        assertFalse(ConversionSettings(format = ImageFormat.PNG).usesQuality)
    }
}
