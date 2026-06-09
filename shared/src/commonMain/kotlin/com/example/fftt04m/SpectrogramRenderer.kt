package com.example.FFTT04M

import kotlin.math.log10
import kotlin.math.pow

/**
 * High-performance renderer that converts FFT magnitudes to pixel colors.
 * Decoupled from any specific UI framework.
 */
class SpectrogramRenderer(
    val width: Int,
    val height: Int,
    private val fftSize: Int,
    private val sampleRate: Float
) {
    // Platform-specific buffer will be injected or managed via expect/actual if needed,
    // but for now we use a raw IntArray which is fast in JVM.
    private val pixels = IntArray(width * height)
    private var writeColumn = 0
    
    // Pre-calculated log mapping
    private val yToBin = IntArray(height) { y ->
        val minFreq = 80f
        val maxFreq = 10000f
        val logMin = log10(minFreq)
        val logMax = log10(maxFreq)
        val logF = logMax - (y.toFloat() / height) * (logMax - logMin)
        val freq = 10.0.pow(logF.toDouble()).toFloat()
        (freq * fftSize / sampleRate).toInt().coerceIn(0, fftSize / 2 - 1)
    }

    /**
     * Process a new column of magnitudes and update the pixel buffer.
     * Parallelizable across multiple columns if needed.
     */
    fun addColumn(magnitudes: FloatArray, lut: IntArray): IntArray {
        for (y in 0 until height) {
            val mag = magnitudes[yToBin[y]]
            val color = lut[(mag * 255).toInt().coerceIn(0, 255)]
            pixels[y * width + writeColumn] = color
        }
        writeColumn = (writeColumn + 1) % width
        return pixels
    }

    fun getWriteOffset(): Int = writeColumn

    fun clear() {
        pixels.fill(0xFF000000.toInt())
        writeColumn = 0
    }
}
