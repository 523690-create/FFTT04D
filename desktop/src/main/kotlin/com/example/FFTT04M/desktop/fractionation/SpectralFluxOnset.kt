package com.example.FFTT04M.desktop.fractionation

import com.example.FFTT04M.desktop.cough.CoughDsp
import com.example.FFTT04M.desktop.cough.FFTUtils
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Method 2 — Spectral Flux Onset.
 * Half-wave-rectified frame-to-frame magnitude increase → adaptive threshold → rising-edge onset.
 * Optional high-frequency variant sums only bins above hfCutoffHz.
 */
class SpectralFluxOnset(
    private val sensitivityFactor: Float = 1.2f,
    private val minOnsetIntervalMs: Int = 150,
    private val frameMs: Double = 25.0,
    private val hopMs: Double = 10.0,
    private val smoothingWindowFrames: Int = 5,
    private val historyFrames: Int = 150,
    private val hfCutoffHz: Double = 0.0,   // 0 = full spectrum
) : Fractionator {

    override val name = "Spectral Flux Onset"

    override fun fractionate(x: FloatArray, sr: Int): List<Segment> {
        if (x.isEmpty()) return emptyList()
        val win = max(8, (frameMs / 1000 * sr).roundToInt())
        val hop = max(1, (hopMs / 1000 * sr).roundToInt())
        val fftSize = FFTUtils.nextPowerOfTwo(win)
        val half = fftSize / 2
        val minHoldFrames = max(1, (minOnsetIntervalMs / hopMs).roundToInt())
        val hfBin = if (hfCutoffHz > 0) (hfCutoffHz * fftSize / sr).toInt().coerceIn(0, half) else 0

        val numFrames = max(0, (x.size - win) / hop + 1)
        if (numFrames < 2) return listOf(Segment(0, (x.size.toDouble() / sr * 1000).roundToInt()))

        // Compute magnitude spectra
        val mags = Array(numFrames) { f ->
            CoughDsp.magnitudeSpectrum(FloatArray(win) { x[f * hop + it] }, fftSize, window = true)
        }

        // Spectral flux (half-wave-rectified)
        val flux = FloatArray(numFrames)
        for (i in 1 until numFrames) {
            val prev = mags[i - 1]; val cur = mags[i]
            var sf = 0f
            val lo = hfBin; val hi = half
            for (k in lo until hi) { val d = cur[k] - prev[k]; if (d > 0) sf += d }
            flux[i] = sf
        }

        // Moving-average smoothing
        val smooth = FloatArray(numFrames)
        for (i in flux.indices) {
            val a = max(0, i - smoothingWindowFrames / 2)
            val b = min(numFrames, i + smoothingWindowFrames / 2 + 1)
            var s = 0f; for (j in a until b) s += flux[j]; smooth[i] = s / (b - a)
        }

        // Rising-edge onsets with adaptive threshold and hold-off
        val onsetFrames = mutableListOf<Int>()
        var holdOff = 0
        for (i in 1 until smooth.size) {
            if (holdOff > 0) { holdOff--; continue }
            val histStart = max(0, i - historyFrames)
            val thr = CoughDsp.median(smooth.copyOfRange(histStart, i)) * sensitivityFactor
            if (smooth[i] > thr && smooth[i] > smooth[i - 1]) {
                onsetFrames.add(i); holdOff = minHoldFrames
            }
        }

        if (onsetFrames.isEmpty()) {
            return listOf(Segment(0, (x.size.toDouble() / sr * 1000).roundToInt()))
        }

        val segments = mutableListOf<Segment>()
        val boundaries = mutableListOf(0) + onsetFrames + listOf(numFrames)
        for (b in 0 until boundaries.size - 1) {
            val startMs = (boundaries[b] * hop.toDouble() / sr * 1000).roundToInt()
            val endSample = (boundaries[b + 1] * hop + win).coerceAtMost(x.size)
            val endMs = (endSample.toDouble() / sr * 1000).roundToInt()
            if (endMs > startMs) segments.add(Segment(startMs, endMs))
        }
        return segments
    }
}
