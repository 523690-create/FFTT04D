package com.example.FFTT04M.desktop.fractionation

import com.example.FFTT04M.desktop.cough.CoughDsp
import com.example.FFTT04M.desktop.cough.FFTUtils
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Method 3 — Syllable Nucleus.
 * Mermelstein-style: log-RMS loudness envelope → local-maximum peaks → valley-to-valley segments.
 * Segments span from the valley before each peak to the valley after it.
 */
class SyllableNucleus(
    private val frameMs: Double = 25.0,
    private val hopMs: Double = 10.0,
    private val smoothingMs: Double = 75.0,
    private val minProminenceDb: Double = 4.0,
    private val minPeakIntervalMs: Int = 120,
    private val minAbsLoudnessDb: Double = -55.0,
) : Fractionator {

    override val name = "Syllable Nucleus"

    override fun fractionate(x: FloatArray, sr: Int): List<Segment> {
        if (x.isEmpty()) return emptyList()
        val win = max(1, (frameMs / 1000 * sr).roundToInt())
        val hop = max(1, (hopMs / 1000 * sr).roundToInt())

        val rms = CoughDsp.rmsEnvelope(x, win, hop)
        if (rms.size < 3) return listOf(Segment(0, (x.size.toDouble() / sr * 1000).roundToInt()))

        val loudness = FloatArray(rms.size) { log10(rms[it].toDouble().coerceAtLeast(1e-7) + 1e-7).toFloat() }

        // Smooth loudness with moving average
        val smoothFrames = max(1, (smoothingMs / hopMs).roundToInt())
        val smooth = FloatArray(rms.size)
        for (i in rms.indices) {
            val a = max(0, i - smoothFrames / 2); val b = min(rms.size, i + smoothFrames / 2 + 1)
            var s = 0f; for (j in a until b) s += loudness[j]; smooth[i] = s / (b - a)
        }

        val minLoudness = log10(1e-7 + Math.pow(10.0, minAbsLoudnessDb / 20)).toFloat()
        val minPeakFrames = max(1, (minPeakIntervalMs / hopMs).roundToInt())

        // Find local maxima
        val peaks = mutableListOf<Int>()
        for (i in 1 until smooth.size - 1) {
            if (smooth[i] > smooth[i - 1] && smooth[i] > smooth[i + 1] && smooth[i] > minLoudness) {
                peaks.add(i)
            }
        }

        // Filter by minimum prominence relative to surrounding valleys
        val prominentPeaks = peaks.filter { p ->
            val leftVal = (0 until p).minOfOrNull { smooth[it] } ?: smooth[0]
            val rightVal = (p + 1 until smooth.size).minOfOrNull { smooth[it] } ?: smooth.last()
            smooth[p] - maxOf(leftVal, rightVal) > minProminenceDb / 20.0
        }.toMutableList()

        // Enforce min peak interval (greedy, keep highest)
        val filtered = mutableListOf<Int>()
        var lastPeak = -minPeakFrames * 2
        for (p in prominentPeaks.sortedByDescending { smooth[it] }) {
            if (filtered.none { kotlin.math.abs(it - p) < minPeakFrames }) filtered.add(p)
        }
        filtered.sort()

        if (filtered.isEmpty()) return listOf(Segment(0, (x.size.toDouble() / sr * 1000).roundToInt()))

        // Segment: valley-to-valley around each peak
        val segments = mutableListOf<Segment>()
        val boundaries = mutableListOf(0)
        for (i in 0 until filtered.size - 1) {
            val a = filtered[i]; val b = filtered[i + 1]
            var valley = a + 1; var valleyVal = smooth[valley]
            for (j in a + 1 until b) if (smooth[j] < valleyVal) { valley = j; valleyVal = smooth[j] }
            boundaries.add(valley)
        }
        boundaries.add(smooth.size)

        for (b in 0 until boundaries.size - 1) {
            val startMs = (boundaries[b] * hop.toDouble() / sr * 1000).roundToInt()
            val endSample = (boundaries[b + 1] * hop + win).coerceAtMost(x.size)
            val endMs = (endSample.toDouble() / sr * 1000).roundToInt()
            if (endMs > startMs) segments.add(Segment(startMs, endMs))
        }
        return segments
    }
}
