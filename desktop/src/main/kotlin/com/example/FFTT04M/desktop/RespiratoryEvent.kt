package com.example.FFTT04M.desktop

import com.example.FFTT04M.desktop.cough.FFTUtils
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Ring-buffer LOOK-BACK respiratory-event analyzer (the keystone). Rather than triggering on the quiet
 * inspiration (unreliable) or blindly on the loudest frame (wrong phase), it: (1) finds the loud event
 * (reliable trigger), (2) scans BACKWARD to the glottal-closure GAP and then the INSPIRATION onset
 * (correct phase), and (3) describes the whole aligned event. This gives a reliable AND phase-correct
 * anchor, which the forward "first-rise" detector could not (measured: forward anchoring underperformed).
 *
 * Produces a subtype-naive feature vector spanning the phases + periodicity + harmonicity, so ONE model
 * can separate expiration / cough / sneeze / snore / speech:
 *   inspiration: inspDurMs, inspCentroidSlope, inspFlat  (shared onset — kept for context)
 *   glottal gap: gapDepth, gapDurMs                       (cough-unique: silence between inhale & burst)
 *   burst:       attackMs, decayMs, widthMs, centroidSweep, hfRatio  (cough sharp/short; sneeze longer/HF)
 *   periodicity: snoreScore, lowFreqRatio                 (snore: low-freq periodic flutter)
 *   voicing:     pitchStrength                            (speech: harmonic)
 *   repetition:  nEvents                                  (repeated coughs)
 */
object RespiratoryEvent {

    val names = listOf(
        "inspDurMs", "inspCentroidSlope", "inspFlat", "gapDepth", "gapDurMs",
        "attackMs", "decayMs", "widthMs", "centroidSweep", "hfRatio",
        "snoreScore", "lowFreqRatio", "pitchStrength", "nEvents", "eventPeakRatio",
        "inspRiseRate", "inspPeakFlow",   // inspiration RAPIDITY (hypothesis: faster/steeper before a cough)
        "gapFloorNorm", "gapSharpness")   // gapDepth fix (2026-07-10): gapDepth alone saturates near 1.0 for
    // ANY loud event with a quiet moment beforehand (peak-relative, floor-agnostic) — hard-negative mining
    // showed breathing clips hitting gapDepth 0.85-1.00 just as often as coughs. gapFloorNorm instead asks
    // "how close to TRUE SILENCE does the gap actually get" (floor-relative, 0=true silence, 1=barely
    // dipped); gapSharpness asks "how V-shaped is the notch" (both the fall INTO the gap and the rise OUT
    // of it must be steep — a real glottal closure snaps shut and reopens, a breath's envelope just
    // wanders). Neither replaces gapDepth (kept for back-compat / ablation comparison) — both are additive.

    private const val WIN = 1024
    private const val EPS = 1e-9

    fun extract(x: FloatArray, sr: Int): DoubleArray {
        val hop = (sr * 0.010).toInt().coerceAtLeast(1)
        val nf = if (x.size < WIN) 0 else (x.size - WIN) / hop + 1
        if (nf < 12) return DoubleArray(names.size)
        val hann = FloatArray(WIN) { 0.5f - 0.5f * cos(2f * PI.toFloat() * it / (WIN - 1)) }
        val binHz = sr.toDouble() / WIN; val hiBin = (8000.0 / binHz).toInt().coerceIn(4, WIN / 2 - 1)
        val lowBin = (300.0 / binHz).toInt().coerceAtLeast(1); val hf2k = (2000.0 / binHz).toInt().coerceAtMost(hiBin - 1)
        val rms = DoubleArray(nf); val cen = DoubleArray(nf); val flat = DoubleArray(nf)
        var lowE = 0.0; var totE = 0.0; var hfE = 0.0
        val re = FloatArray(WIN); val im = FloatArray(WIN)
        for (fr in 0 until nf) {
            val o = fr * hop; var e = 0.0
            for (i in 0 until WIN) { re[i] = x[o + i] * hann[i]; im[i] = 0f; e += x[o + i].toDouble() * x[o + i] }
            rms[fr] = sqrt(e / WIN); FFTUtils.compute(re, im)
            var sM = 0.0; var sfM = 0.0; var sLn = 0.0
            for (k in 1 until hiBin) {
                val mag = sqrt((re[k] * re[k] + im[k] * im[k]).toDouble()); sM += mag; sfM += k * binHz * mag; sLn += ln(mag + EPS)
                totE += mag; if (k < lowBin) lowE += mag; if (k > hf2k) hfE += mag
            }
            cen[fr] = if (sM > EPS) sfM / sM else 0.0
            flat[fr] = Math.exp(sLn / (hiBin - 1)) / (sM / (hiBin - 1) + EPS)
        }
        val fps = sr.toDouble() / hop
        val sortedR = rms.sortedArray(); val floor = sortedR[(0.2 * (nf - 1)).toInt()].coerceAtLeast(EPS)

        // (1) loud event = peak RMS frame (reliable)
        var pk = 0; for (i in 1 until nf) if (rms[i] > rms[pk]) pk = i
        val pkV = rms[pk].coerceAtLeast(EPS)

        // (2) look back: glottal GAP = deepest RMS dip in the ~300 ms before the peak
        val gWlo = (pk - (0.30 * fps).toInt()).coerceAtLeast(0)
        var gap = pk; for (i in gWlo until pk) if (rms[i] < rms[gap]) gap = i
        val gapDepth = 1.0 - rms[gap] / pkV
        // gap duration: contiguous frames around `gap` below 30% of peak
        var gl = gap; while (gl > gWlo && rms[gl] < 0.3 * pkV) gl--
        var gr = gap; while (gr < pk && rms[gr] < 0.3 * pkV) gr++
        val gapDurMs = (gr - gl) * 1000.0 / fps

        // (2b) inspiration = rising broadband airflow before the gap
        var inspPeak = gl; for (i in (gl - (0.6 * fps).toInt()).coerceAtLeast(0)..gl) if (rms[i] > rms[inspPeak]) inspPeak = i
        var inspOnset = inspPeak; while (inspOnset > 0 && rms[inspOnset] > 1.3 * floor) inspOnset--
        val inspDurMs = ((inspPeak - inspOnset).coerceAtLeast(0)) * 1000.0 / fps
        val inspCentroidSlope = if (inspPeak - inspOnset >= 3) slope(cen, inspOnset, inspPeak) else 0.0
        val inspFlat = if (inspPeak > inspOnset) (inspOnset until inspPeak).sumOf { flat[it] } / (inspPeak - inspOnset) else flat[pk]

        // (3) burst around the peak
        var a = pk; while (a > 0 && rms[a] > 0.1 * pkV) a--
        var d = pk; while (d < nf - 1 && rms[d] > 0.1 * pkV) d++
        val attackMs = (pk - a) * 1000.0 / fps; val decayMs = (d - pk) * 1000.0 / fps
        val widthMs = rms.count { it > 0.5 * pkV } * 1000.0 / fps
        val cAfter = (pk + (0.15 * fps).toInt()).coerceAtMost(nf - 1)
        val centroidSweep = cen[pk] - cen[cAfter]                 // cough sweeps high→low → positive
        val hfRatio = if (totE > EPS) hfE / totE else 0.0

        // periodicity (snore) + voicing (speech) on the loudest ~4096-sample window at the peak
        val center = (pk * hop).coerceIn(0, (x.size - 1)); val half = 2048
        val s0 = (center - half).coerceAtLeast(0); val s1 = (center + half).coerceAtMost(x.size)
        val seg = x.copyOfRange(s0, s1)
        val snoreScore = autocorrPeak(seg, sr / 100, sr / 20)     // 20–100 Hz flutter
        val pitchStrength = autocorrPeak(seg, sr / 400, sr / 80)  // 80–400 Hz voice
        val lowFreqRatio = if (totE > EPS) lowE / totE else 0.0

        // repetition
        var nEvents = 0; var i = 0; val sep = (0.15 * fps).toInt().coerceAtLeast(1)
        while (i < nf) { if (rms[i] > 0.4 * pkV) { nEvents++; i += sep } else i++ }
        val eventPeakRatio = pkV / (sortedR[(0.5 * (nf - 1)).toInt()].coerceAtLeast(EPS))

        // inspiration RAPIDITY: how fast airflow ramps up during the inhale (hypothesis: steeper before
        // a cough — a forced pre-cough inspiration is quicker/deeper than a relaxed tidal breath).
        val inspDurSec = (inspDurMs / 1000.0).coerceAtLeast(0.01)
        val inspRiseRate = ((rms[inspPeak] - rms[inspOnset]).coerceAtLeast(0.0) / pkV) / inspDurSec  // norm. RMS rise per sec
        val inspPeakFlow = rms[inspPeak] / pkV                                                        // inhale loudness vs the burst

        // gapDepth FIX: floor-relative depth (0 = gap reaches TRUE silence, 1 = barely dipped below peak)
        // instead of peak-relative-only, which saturates near 1.0 for any quiet moment before a loud event
        // regardless of whether it's a genuine glottal closure.
        val range = (pkV - floor).coerceAtLeast(EPS)
        val gapFloorNorm = ((rms[gap] - floor) / range).coerceIn(0.0, 1.0)
        // gapSharpness: how V-shaped the notch is — both the fall INTO the gap (inspPeak->gap) and the
        // rise OUT of it (gap->pk) must be steep (normalized RMS change per second, relative to range).
        val fallSec = ((gap - inspPeak).coerceAtLeast(1)) / fps
        val riseSec = ((pk - gap).coerceAtLeast(1)) / fps
        val fallRate = (rms[inspPeak] - rms[gap]).coerceAtLeast(0.0) / range / fallSec
        val riseRate = (pkV - rms[gap]).coerceAtLeast(0.0) / range / riseSec
        val gapSharpness = minOf(fallRate, riseRate)

        return doubleArrayOf(inspDurMs, inspCentroidSlope, inspFlat, gapDepth, gapDurMs,
            attackMs, decayMs, widthMs, centroidSweep, hfRatio,
            snoreScore, lowFreqRatio, pitchStrength, nEvents.toDouble(), eventPeakRatio,
            inspRiseRate, inspPeakFlow, gapFloorNorm, gapSharpness)
    }

    private fun slope(arr: DoubleArray, lo: Int, hi: Int): Double {
        val n = hi - lo; if (n < 3) return 0.0
        val mx = (n - 1) / 2.0; var my = 0.0; for (i in lo until hi) my += arr[i]; my /= n
        var sxy = 0.0; var sxx = 0.0; for (i in 0 until n) { val dx = i - mx; sxy += dx * (arr[lo + i] - my); sxx += dx * dx }
        return if (sxx > EPS) sxy / sxx else 0.0
    }

    private fun autocorrPeak(seg: FloatArray, loLag: Int, hiLag: Int): Double {
        if (seg.size < 64) return 0.0
        val n = FFTUtils.nextPowerOfTwo(2 * seg.size); val re = FloatArray(n); val im = FloatArray(n)
        for (i in seg.indices) re[i] = seg[i]
        FFTUtils.compute(re, im)
        for (k in 0 until n) { re[k] = re[k] * re[k] + im[k] * im[k]; im[k] = 0f }
        FFTUtils.inverse(re, im)
        val r0 = re[0].toDouble(); if (r0 < EPS) return 0.0
        var pk = 0.0; val hi = hiLag.coerceAtMost(seg.size - 1)
        for (lag in loLag..hi) pk = maxOf(pk, re[lag].toDouble() / r0)
        return pk
    }
}
