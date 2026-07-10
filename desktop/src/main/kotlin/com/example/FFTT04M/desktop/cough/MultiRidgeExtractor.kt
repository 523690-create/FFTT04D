package com.example.FFTT04M.desktop.cough

import kotlin.math.abs

/**
 * Multi-event extension of [RidgeExtractor]. A cough segment's 300–2000 Hz ridge often contains
 * SEVERAL short parabolic chirps ("squiggles") back to back, not one — [RidgeExtractor.extract] fits a
 * single global parabola and blurs them together. This groups the frame-level ridge points into
 * contiguous runs (breaking on any gap where a frame failed the prominence gate), then RECURSIVELY
 * splits each run: fit one parabola to the whole run, and if it fits well (R² above threshold) or the
 * run is already short/small, accept it as one event; otherwise split at the run's deepest interior
 * frequency valley (smoothed, to dodge single-frame jitter) and recurse on each half. A raw local-max
 * peak-picker was tried first and badly over-segmented on frame-to-frame noise (a real vertex needs
 * evidence across ~all its points, not just being taller than its immediate neighbours) — the
 * goodness-of-fit gate is what keeps this from splitting noise into dozens of spurious events.
 */
class MultiRidgeExtractor(private val cfg: CoughAnalysisConfig = CoughAnalysisConfig()) {
    private val single = RidgeExtractor(cfg)

    /** One detected chirp event. Times are seconds relative to the [detect] call's startSample. */
    data class Event(
        val t0Sec: Double, val t1Sec: Double,
        val vertexTimeSec: Double, val vertexFreqHz: Double,
        val curvature: Double, val rSquared: Double, val frameCount: Int, val meanEnergy: Double,
    )

    /** A single-parabola fit is accepted as one event once its R² reaches this; below it, the run gets
     *  split at its deepest interior valley and each half is re-evaluated recursively. Real per-frame
     *  spectral-peak tracking is noisy — even a genuine single chirp rarely fits much above this — so
     *  this is deliberately lenient; it exists to catch runs that are CLEARLY two-humped, not to demand
     *  a clean parabola. */
    var minR2ForAccept: Double = 0.2

    /** Below this many points a run is never split further — 3 points always fit a parabola perfectly
     *  (R²=1 is a degeneracy, not a signal), so splitting must stop well before points get that scarce. */
    var minPointsToSplit: Int = 16

    /** Below this time span a run is never split further, regardless of point count or fit quality —
     *  a real vertex-to-vertex chirp gap is at least this wide. */
    var minEventDurationSec: Double = 0.15

    /** Frame-to-frame gap (seconds) beyond which a run breaks. Real chirps often have a frame or two of
     *  weak in-band prominence mid-ridge (noisy cough audio, not silence) — bridge those so the R²-gated
     *  splitter (not upstream gap fragmentation) is what decides event boundaries. */
    var maxGapSec: Double = 0.08

    fun detect(x: FloatArray, startSample: Int, endSample: Int, sampleRate: Int): List<Event> {
        val points = single.collectPoints(x, startSample, endSample, sampleRate)
        if (points.isEmpty()) return emptyList()
        val gapSec = maxGapSec
        val runs = ArrayList<MutableList<RidgeExtractor.RidgePoint>>()
        for (p in points) {
            val cur = runs.lastOrNull()
            if (cur != null && p.timeSec - cur.last().timeSec <= gapSec) cur.add(p) else runs.add(arrayListOf(p))
        }
        val events = ArrayList<Event>()
        for (run in runs) events += splitRun(run)
        return events
    }

    private fun splitRun(run: List<RidgeExtractor.RidgePoint>): List<Event> {
        val whole = fitEvent(run) ?: return emptyList()
        val dur = run.last().timeSec - run.first().timeSec
        if (run.size < minPointsToSplit || dur < minEventDurationSec * 2 || whole.rSquared >= minR2ForAccept) {
            return listOf(whole)
        }

        // 9-point (~90 ms) moving average on frequency to find the deepest INTERIOR valley — the
        // boundary between two humps — without chasing frame-to-frame jitter.
        val f = DoubleArray(run.size) { run[it].freqHz }
        val sm = DoubleArray(run.size) { i ->
            val lo = (i - 4).coerceAtLeast(0); val hi = (i + 4).coerceAtMost(run.size - 1)
            (lo..hi).sumOf { f[it] } / (hi - lo + 1)
        }
        val margin = (minEventDurationSec / ((run.last().timeSec - run.first().timeSec) / (run.size - 1).coerceAtLeast(1)).coerceAtLeast(1e-6)).toInt().coerceAtLeast(3)
        if (run.size - 2 * margin < 3) return listOf(whole)
        var vi = -1; var vMin = Double.MAX_VALUE
        for (i in margin until run.size - margin) if (sm[i] < vMin) { vMin = sm[i]; vi = i }
        // Split point must leave both halves with enough points to fit their own parabola, else give up
        // and accept the whole run as-is (best achievable single event for this run).
        if (vi < 2 || run.size - 1 - vi < 2) return listOf(whole)

        val left = splitRun(run.subList(0, vi + 1))
        val right = splitRun(run.subList(vi, run.size))
        return left + right
    }

    private fun fitEvent(pts: List<RidgeExtractor.RidgePoint>): Event? {
        if (pts.isEmpty()) return null
        if (pts.size < 3) {
            val t0 = pts.first().timeSec; val t1 = pts.last().timeSec
            val vertex = pts.maxByOrNull { it.freqHz } ?: pts.first()
            return Event(t0, t1, vertex.timeSec, vertex.freqHz, 0.0, 0.0, pts.size, pts.sumOf { it.energy } / pts.size)
        }
        val t = DoubleArray(pts.size) { pts[it].timeSec }
        val f = DoubleArray(pts.size) { pts[it].freqHz }
        val fit = CoughDsp.fitParabola(t, f) ?: return null
        val (a, b, c, r2) = fit
        val t0 = t.first(); val t1 = t.last()
        fun fAt(tt: Double) = a * tt * tt + b * tt + c
        val vertexRaw = if (abs(a) > 1e-9) -b / (2 * a) else if (fAt(t0) >= fAt(t1)) t0 else t1
        val vertexT = vertexRaw.coerceIn(t0, t1)
        return Event(t0, t1, vertexT, fAt(vertexT), a, r2, pts.size, pts.sumOf { it.energy } / pts.size)
    }
}
