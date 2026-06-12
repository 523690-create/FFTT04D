package com.example.FFTT04M.desktop

import com.example.FFTT04M.desktop.cough.FFTUtils
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Renders 512×512 analysis images for an ALLDATA clip, using the Magma colour map and the SAME
 * transforms the mobile app exposes:
 *
 *  - [renderFftPng]: FFT spectrogram, size 2048 / step 1024, log-magnitude, **renormalized** to the
 *    clip's full intensity range. Written as PNG.
 *  - [renderCwtJpg]: Morlet CWT scalogram — resampled to 20 kHz, 100 scales, max level 10
 *    (`maxScale = 2^(level+3)`), w0 = 6, **no threshold**, **renormalized**. Written as JPEG.
 *
 * The CWT math is ported verbatim from `WaveletActivity.runCwt` (FFT-domain Morlet convolution);
 * "order" is a DWT-only parameter and has no effect on the Morlet CWT, matching the mobile engine.
 */
object SpectrogramRenderer {

    private const val SIZE = 512

    // FFT spectrogram
    private const val FFT_SIZE = 2048
    private const val FFT_STEP = 1024

    // CWT
    private const val CWT_TARGET_HZ = 20000f
    private const val CWT_LEVEL = 10
    private const val CWT_SCALES = 100
    private const val CWT_W0 = 6.0f
    private const val CWT_MAX_SAMPLES = 60000   // same safety cap as the mobile engine

    /**
     * Use the GPU (cuFFT) for the CWT inverse-FFT bank when available. Default OFF: with the
     * CPU-side multiply/magnitude + full PCIe transfer, the simple cuFFT path is transfer-bound and
     * loses to a many-core CPU. A worthwhile GPU path needs on-device multiply+magnitude (NVRTC).
     */
    @Volatile var useGpu = false

    // The 100 Morlet frequency-domain kernels depend only on the padded length, not the signal,
    // so cache them per padded size and reuse across all clips (big CPU saving vs rebuilding each).
    private val kernelCache = HashMap<Int, Array<FloatArray>>()

    @Synchronized
    private fun kernelsFor(padded: Int): Array<FloatArray> = kernelCache.getOrPut(padded) {
        val minScale = 1f
        val maxScale = 2f.pow(CWT_LEVEL + 3)
        Array(CWT_SCALES) { s ->
            val scale = minScale * (maxScale / minScale).pow(s.toFloat() / (CWT_SCALES - 1))
            val sqrtScale = sqrt(scale)
            FloatArray(padded) { i ->
                val omega = if (i <= padded / 2) 2f * PI.toFloat() * i / padded
                            else 2f * PI.toFloat() * (i - padded) / padded
                val valExp = -0.5f * (scale * omega - CWT_W0).pow(2)
                if (valExp > -20f) exp(valExp) * sqrtScale else 0f
            }
        }
    }

    // ---- public API ----------------------------------------------------------------------------

    fun renderFftPng(pcm: FloatArray, sampleRate: Int, out: File) {
        val grid = fftSpectrogram(pcm)              // [freqLow→high][time]
        toImage(grid).let { ImageIO.write(it, "png", out) }
    }

    fun renderCwtJpg(pcm: FloatArray, sampleRate: Int, out: File) {
        val grid = cwtScalogram(pcm, sampleRate)    // [freqLow→high][time]
        toImage(grid).let { ImageIO.write(it, "jpg", out) }
    }

    // ---- FFT spectrogram -----------------------------------------------------------------------

    /** STFT log-magnitude grid `[bin][frame]`, bins ordered low→high frequency. */
    private fun fftSpectrogram(pcm: FloatArray): Array<FloatArray> {
        val n = pcm.size
        val bins = FFT_SIZE / 2
        val frames = if (n < FFT_SIZE) 1 else (n - FFT_SIZE) / FFT_STEP + 1
        val hann = FloatArray(FFT_SIZE) { 0.5f - 0.5f * cos(2f * PI.toFloat() * it / (FFT_SIZE - 1)) }
        val grid = Array(bins) { FloatArray(frames) }
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        for (f in 0 until frames) {
            val start = f * FFT_STEP
            for (i in 0 until FFT_SIZE) {
                val s = start + i
                re[i] = if (s < n) pcm[s] * hann[i] else 0f
                im[i] = 0f
            }
            FFTUtils.compute(re, im)
            for (b in 0 until bins) {
                val mag = sqrt(re[b] * re[b] + im[b] * im[b])
                grid[b][f] = 20f * (ln(mag + 1e-9f) / LN10)   // dB-ish log magnitude
            }
        }
        return grid
    }

    // ---- Morlet CWT scalogram (ported from WaveletActivity.runCwt) ------------------------------

    /** CWT magnitude grid `[row][time]`, rows ordered low→high frequency (row 0 = lowest). */
    private fun cwtScalogram(pcm: FloatArray, sampleRate: Int): Array<FloatArray> {
        var data = resample(pcm, sampleRate.toFloat(), CWT_TARGET_HZ)
        if (data.size > CWT_MAX_SAMPLES) data = data.copyOf(CWT_MAX_SAMPLES)
        val n = data.size
        if (n == 0) return arrayOf(FloatArray(1))

        val padded = FFTUtils.nextPowerOfTwo(n)
        val sigRe = FloatArray(padded)
        val sigIm = FloatArray(padded)
        for (i in 0 until n) sigRe[i] = data[i]
        FFTUtils.compute(sigRe, sigIm)

        val kernels = kernelsFor(padded)
        // coefficients[scale][time]; scale 0 = smallest = highest frequency.
        val coeff = if (useGpu && GpuFft.available()) gpuBank(sigRe, sigIm, kernels, padded, n)
                    else cpuBank(sigRe, sigIm, kernels, padded, n)
        // Reorder rows so row 0 = lowest frequency (largest scale), to match the FFT image.
        return Array(CWT_SCALES) { r -> coeff[CWT_SCALES - 1 - r] }
    }

    /** CPU path: per-scale spectral product + inverse FFT + magnitude. */
    private fun cpuBank(sigRe: FloatArray, sigIm: FloatArray, kernels: Array<FloatArray>,
                        padded: Int, n: Int): Array<FloatArray> {
        val coeff = Array(CWT_SCALES) { FloatArray(n) }
        val re = FloatArray(padded)
        val im = FloatArray(padded)
        for (s in 0 until CWT_SCALES) {
            val k = kernels[s]
            for (i in 0 until padded) { re[i] = sigRe[i] * k[i]; im[i] = sigIm[i] * k[i] }
            FFTUtils.inverse(re, im)
            for (i in 0 until n) coeff[s][i] = sqrt(re[i] * re[i] + im[i] * im[i])
        }
        return coeff
    }

    /** GPU path: build all 100 spectral products, one batched cuFFT inverse, then magnitudes. */
    private fun gpuBank(sigRe: FloatArray, sigIm: FloatArray, kernels: Array<FloatArray>,
                        padded: Int, n: Int): Array<FloatArray> {
        val interleaved = FloatArray(2 * CWT_SCALES * padded)
        for (s in 0 until CWT_SCALES) {
            val k = kernels[s]
            val base = 2 * s * padded
            for (i in 0 until padded) {
                interleaved[base + 2 * i] = sigRe[i] * k[i]
                interleaved[base + 2 * i + 1] = sigIm[i] * k[i]
            }
        }
        val inv = GpuFft.inverseBatch(interleaved, padded, CWT_SCALES)
            ?: return cpuBank(sigRe, sigIm, kernels, padded, n)   // fall back if the GPU call fails
        val coeff = Array(CWT_SCALES) { FloatArray(n) }
        for (s in 0 until CWT_SCALES) {
            val base = 2 * s * padded
            val cs = coeff[s]
            for (i in 0 until n) {
                val re = inv[base + 2 * i]; val im = inv[base + 2 * i + 1]
                cs[i] = sqrt(re * re + im * im)
            }
        }
        return coeff
    }

    private fun resample(input: FloatArray, from: Float, to: Float): FloatArray {
        if (kotlin.math.abs(from - to) < 1f || input.isEmpty()) return input
        val ratio = to / from
        val out = FloatArray((input.size * ratio).toInt().coerceAtLeast(1))
        for (i in out.indices) {
            val src = i / ratio
            val i0 = src.toInt().coerceIn(0, input.size - 1)
            val i1 = (i0 + 1).coerceIn(0, input.size - 1)
            val frac = src - i0
            out[i] = input[i0] * (1 - frac) + input[i1] * frac
        }
        return out
    }

    // ---- grid -> image (renormalize + Magma + scale to 512×512) ---------------------------------

    /** [grid] is `[row][col]` with row ordered low→high frequency; rendered high-freq-at-top. */
    private fun toImage(grid: Array<FloatArray>): BufferedImage {
        val rows = grid.size
        val cols = grid.firstOrNull()?.size ?: 1
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (row in grid) for (v in row) { if (v < lo) lo = v; if (v > hi) hi = v }
        val span = if (hi > lo) hi - lo else 1f

        val img = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until SIZE) {
            // top row = highest frequency
            val r = ((1f - y / (SIZE - 1f)) * (rows - 1)).roundToInt().coerceIn(0, rows - 1)
            val gr = grid[r]
            for (x in 0 until SIZE) {
                val c = (x / (SIZE - 1f) * (cols - 1)).roundToInt().coerceIn(0, cols - 1)
                val t = ((gr[c] - lo) / span).coerceIn(0f, 1f)
                img.setRGB(x, y, MAGMA[(t * 255f).toInt().coerceIn(0, 255)])
            }
        }
        return img
    }

    // ---- Magma LUT (ported from the mobile ColorMaps anchors) -----------------------------------

    private const val LN10 = 2.302585f

    private val MAGMA: IntArray = buildLut(
        intArrayOf(
            0x000004, 0x140e36, 0x3b0f70, 0x641a80, 0x8c2981,
            0xb73779, 0xde4968, 0xf7705c, 0xfe9f6d, 0xfcfdbf
        )
    )

    /** Expand RGB anchor stops into a 256-entry ARGB LUT (linear interpolation between stops). */
    private fun buildLut(stops: IntArray): IntArray {
        val lut = IntArray(256)
        val segs = stops.size - 1
        for (i in 0..255) {
            val t = i / 255f * segs
            val seg = t.toInt().coerceAtMost(segs - 1)
            val frac = t - seg
            lut[i] = lerp(stops[seg], stops[seg + 1], frac)
        }
        return lut
    }

    private fun lerp(c1: Int, c2: Int, f: Float): Int {
        val r = (((c1 shr 16) and 0xFF) + (((c2 shr 16) and 0xFF) - ((c1 shr 16) and 0xFF)) * f).toInt()
        val g = (((c1 shr 8) and 0xFF) + (((c2 shr 8) and 0xFF) - ((c1 shr 8) and 0xFF)) * f).toInt()
        val b = ((c1 and 0xFF) + ((c2 and 0xFF) - (c1 and 0xFF)) * f).toInt()
        return (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    }
}
