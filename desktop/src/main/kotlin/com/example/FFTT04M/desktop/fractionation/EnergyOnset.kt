package com.example.FFTT04M.desktop.fractionation

import com.example.FFTT04M.desktop.cough.CoughDsp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Method 1 — Energy Onset.
 * RMS envelope → exponential smoothing → adaptive threshold (history-median × sensitivity) →
 * rising-edge onset with hold-off. Segments are onset-to-onset intervals.
 */
class EnergyOnset(
    private val sensitivityFactor: Float = 1.5f,
    private val minOnsetIntervalMs: Int = 200,
    private val frameMs: Double = 25.0,
    private val hopMs: Double = 10.0,
    private val smoothingAlpha: Float = 0.8f,
    private val historyFrames: Int = 150,
) : Fractionator {

    override val name = "Energy Onset"

    override fun fractionate(x: FloatArray, sr: Int): List<Segment> {
        if (x.isEmpty()) return emptyList()
        val win = max(1, (frameMs / 1000 * sr).roundToInt())
        val hop = max(1, (hopMs / 1000 * sr).roundToInt())
        val minHoldFrames = max(1, (minOnsetIntervalMs / hopMs).roundToInt())

        val rms = CoughDsp.rmsEnvelope(x, win, hop)
        if (rms.size < 2) return emptyList()

        // Exponential smoothing
        val smooth = FloatArray(rms.size)
        smooth[0] = rms[0]
        for (i in 1 until rms.size) smooth[i] = smoothingAlpha * rms[i] + (1f - smoothingAlpha) * smooth[i - 1]

        // Rising-edge onsets with adaptive threshold and hold-off
        val onsetFrames = mutableListOf<Int>()
        var holdOff = 0
        for (i in 1 until smooth.size) {
            if (holdOff > 0) { holdOff--; continue }
            val histStart = max(0, i - historyFrames)
            val histSlice = smooth.copyOfRange(histStart, i)
            val thr = CoughDsp.median(histSlice) * sensitivityFactor
            if (smooth[i] > thr && smooth[i] > smooth[i - 1]) {
                onsetFrames.add(i)
                holdOff = minHoldFrames
            }
        }

        if (onsetFrames.isEmpty()) {
            // No onsets → whole clip as one segment
            val durMs = (x.size.toDouble() / sr * 1000).roundToInt()
            return listOf(Segment(0, durMs))
        }

        val segments = mutableListOf<Segment>()
        val boundaries = mutableListOf(0) + onsetFrames + listOf(smooth.size)
        for (b in 0 until boundaries.size - 1) {
            val startMs = (boundaries[b] * hop.toDouble() / sr * 1000).roundToInt()
            val endSample = (boundaries[b + 1] * hop + win).coerceAtMost(x.size)
            val endMs = (endSample.toDouble() / sr * 1000).roundToInt()
            if (endMs > startMs) segments.add(Segment(startMs, endMs))
        }
        return segments
    }
}
