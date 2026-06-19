package com.example.FFTT04M.desktop.fractionation

import com.example.FFTT04M.desktop.cough.CoughDsp
import com.example.FFTT04M.desktop.cough.MfccExtractor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Method 5 — Feature Changepoint.
 * CUSUM on a per-frame feature stream (MFCC-13 + log-energy + ZCR).
 * Detects points where the feature distribution changes significantly.
 */
class FeatureChangepoint(
    private val frameMs: Double = 25.0,
    private val hopMs: Double = 10.0,
    private val cusumKFactor: Double = 0.5,
    private val cusumHThreshold: Double = 3.0,
    private val minSegmentMs: Int = 100,
    private val smoothWindowFrames: Int = 3,
) : Fractionator {

    override val name = "Feature Changepoint"

    private val mfcc = MfccExtractor(numCoeffs = 13, winMs = 25.0, hopMs = 10.0)

    override fun fractionate(x: FloatArray, sr: Int): List<Segment> {
        if (x.isEmpty()) return emptyList()
        val win = max(8, (frameMs / 1000 * sr).roundToInt())
        val hop = max(1, (hopMs / 1000 * sr).roundToInt())
        val minSegFrames = max(1, (minSegmentMs / hopMs).roundToInt())

        val numFrames = max(0, (x.size - win) / hop + 1)
        if (numFrames < 4) return listOf(Segment(0, (x.size.toDouble() / sr * 1000).roundToInt()))

        // Per-frame features: 13 MFCCs + log-energy + ZCR = 15 dims
        val frames = Array(numFrames) { f ->
            val start = f * hop
            val end = (start + win).coerceAtMost(x.size)
            val slice = FloatArray(end - start) { x[start + it] }

            // MFCCs for this frame — read straight from x[start,end). (Previously this built a
            // full-clip-sized zero-padded copy PER FRAME → O(frames × clipLen), the real bottleneck.)
            val mf = mfcc.extract(x, start, end, sr)

            // log-energy
            var energy = 0.0; for (s in slice) energy += s.toDouble() * s
            val logE = if (energy > 1e-12) kotlin.math.log10(energy / slice.size) else -6.0

            // ZCR
            var zc = 0; for (i in 1 until slice.size) if ((slice[i - 1] >= 0) != (slice[i] >= 0)) zc++
            val zcr = zc.toDouble() / slice.size

            DoubleArray(15) { k -> when { k < 13 -> mf.mean[k]; k == 13 -> logE; else -> zcr } }
        }

        // Frame-to-frame Euclidean distance
        val diff = FloatArray(numFrames)
        for (i in 1 until numFrames) {
            var d = 0.0
            for (k in frames[i].indices) { val delta = frames[i][k] - frames[i - 1][k]; d += delta * delta }
            diff[i] = sqrt(d).toFloat()
        }

        // Smooth diff
        val smooth = FloatArray(numFrames)
        for (i in diff.indices) {
            val a = max(0, i - smoothWindowFrames / 2); val b = kotlin.math.min(numFrames, i + smoothWindowFrames / 2 + 1)
            var s = 0f; for (j in a until b) s += diff[j]; smooth[i] = s / (b - a)
        }

        // Estimate drift parameter K from std of diff
        var meanD = 0.0; for (v in smooth) meanD += v; meanD /= smooth.size
        var varD = 0.0; for (v in smooth) { val d = v - meanD; varD += d * d }; varD /= smooth.size
        val K = (cusumKFactor * sqrt(varD)).toFloat()
        val H = (cusumHThreshold * sqrt(varD)).toFloat()

        // CUSUM
        val changepoints = mutableListOf<Int>()
        var sPos = 0f
        var lastCp = 0
        for (i in 1 until smooth.size) {
            sPos = max(0f, sPos + smooth[i] - meanD.toFloat() - K)
            if (sPos > H && i - lastCp >= minSegFrames) {
                changepoints.add(i); sPos = 0f; lastCp = i
            }
        }

        if (changepoints.isEmpty()) return listOf(Segment(0, (x.size.toDouble() / sr * 1000).roundToInt()))

        val segments = mutableListOf<Segment>()
        val boundaries = mutableListOf(0) + changepoints + listOf(numFrames)
        for (b in 0 until boundaries.size - 1) {
            val startMs = (boundaries[b] * hop.toDouble() / sr * 1000).roundToInt()
            val endSample = (boundaries[b + 1] * hop + win).coerceAtMost(x.size)
            val endMs = (endSample.toDouble() / sr * 1000).roundToInt()
            if (endMs > startMs) segments.add(Segment(startMs, endMs))
        }
        return segments
    }
}
