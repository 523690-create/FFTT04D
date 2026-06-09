package com.example.FFTT04M

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

@Composable
fun SpectrogramCanvas(
    latestPixels: IntArray?,
    writeOffset: Int,
    width: Int,
    height: Int,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var currentSize by remember { mutableStateOf(IntSize.Zero) }

    Canvas(modifier = modifier) {
        if (width < 1 || height < 1) return@Canvas

        if (bitmap == null || currentSize.width != width || currentSize.height != height) {
            bitmap = ImageBitmap(width, height)
            currentSize = IntSize(width, height)
        }

        val b = bitmap!!

        if (latestPixels != null) {
            // Update the bitmap with the latest raw pixels
            // On desktop, ImageBitmap.asSkiaBitmap().installPixels is the fastest path
            updateBitmapPixels(b, latestPixels)
        }

        // Draw the circular buffer in two parts to create the scrolling effect
        // Part 1: from writeOffset to end (the older part, now on the left)
        drawImage(
            image = b,
            srcOffset = IntOffset(writeOffset, 0),
            srcSize = IntSize(width - writeOffset, height),
            dstOffset = IntOffset(0, 0),
            dstSize = IntSize(width - writeOffset, height)
        )
        
        // Part 2: from 0 to writeOffset (the newer part, now on the right)
        if (writeOffset > 0) {
            drawImage(
                image = b,
                srcOffset = IntOffset(0, 0),
                srcSize = IntSize(writeOffset, height),
                dstOffset = IntOffset(width - writeOffset, 0),
                dstSize = IntSize(writeOffset, height)
            )
        }
    }
}

/** 
 * Platform-specific bitmap pixel update.
 */
expect fun updateBitmapPixels(bitmap: ImageBitmap, pixels: IntArray)
