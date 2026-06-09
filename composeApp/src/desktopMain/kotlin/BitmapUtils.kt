package com.example.FFTT04M

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.*

actual fun updateBitmapPixels(bitmap: ImageBitmap, pixels: IntArray) {
    val skiaBitmap = bitmap.asSkiaBitmap()
    val width = skiaBitmap.width
    val height = skiaBitmap.height

    val info = ImageInfo(
        colorInfo = ColorInfo(ColorType.BGRA_8888, ColorAlphaType.PREMUL, null),
        width = width,
        height = height
    )
    
    // Convert to ByteArray for Skia's installPixels on JVM
    val bytes = ByteArray(pixels.size * 4)
    for (i in pixels.indices) {
        val p = pixels[i]
        bytes[i * 4] = (p and 0xFF).toByte()
        bytes[i * 4 + 1] = ((p shr 8) and 0xFF).toByte()
        bytes[i * 4 + 2] = ((p shr 16) and 0xFF).toByte()
        bytes[i * 4 + 3] = ((p shr 24) and 0xFF).toByte()
    }
    
    skiaBitmap.installPixels(info, bytes, width * 4)
}
