package com.example.FFTT04M.desktop

import com.example.FFTT04M.desktop.cough.FFTUtils
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * TEMPORAL spectral-trajectory ("cough phase") features — the hypothesis that a cough is a stereotyped
 * sequence (forced inspiration: centroid rising pink→blue · glottal-closure gap · sharp expulsive burst:
 * blue→pink and short), whereas a breath is inspiration → gentle long low-frequency expiration. Static
 * whole-clip features mean-pool this trajectory AWAY, which is exactly why cough≈breath statically.
 *
 * All features describe HOW the spectrum EVOLVES around the loudest event, not its average:
 *   attack/decay/width — the burst's sharpness (cough sharp+short, breath gentle+long);
 *   centroidPre/Post + slopes — the pink↔blue sweep before vs after the burst;
 *   tiltPre/Post — spectral tilt (pink = negative, blue = positive) before vs after;
 *   gapDepth — the pre-burst near-silence (glottal closure; unique to cough);
 *   inspirationRise — centroid rising in the ~0.5 s before the burst (forced inhale);
 *   nPeaks — repeated coughs vs a single breath swell.
 * Cheap DSP (one STFT), interpretable, and capture-alignable (anchored on the burst).
 */
object SpectralDynamics {

    val names = listOf(
        "attackMs", "decayMs", "peakWidthMs", "centroidPre", "centroidPost",
        "centroidSlopePre", "centroidSlopePost", "tiltPre", "tiltPost", "gapDepth",
        "inspirationRise", "nPeaks", "flatMean")

    private const val WIN = 1024
    private const val EPS = 1e-9

    fun extract(x: FloatArray, sr: Int): DoubleArray {
        val hop = (sr * 0.010).toInt().coerceAtLeast(1)          // 10 ms
        val nf = if (x.size < WIN) 0 else (x.size - WIN) / hop + 1
        if (nf < 4) return DoubleArray(names.size)
        val hann = FloatArray(WIN) { 0.5f - 0.5f * cos(2f * PI.toFloat() * it / (WIN - 1)) }
        val binHz = sr.toDouble() / WIN; val bins = WIN / 2
        val hiBin = (8000.0 / binHz).toInt().coerceIn(4, bins - 1)   // tilt/centroid measured to ~8 kHz

        val rms = DoubleArray(nf); val cen = DoubleArray(nf); val tilt = DoubleArray(nf); val flat = DoubleArray(nf)
        val re = FloatArray(WIN); val im = FloatArray(WIN)
        val fMean = (1 until hiBin).sumOf { it * binHz } / (hiBin - 1)   // freq mean for tilt regression
        var fVar = 0.0; for (k in 1 until hiBin) { val d = k * binHz - fMean; fVar += d * d }
        for (fr in 0 until nf) {
            val o = fr * hop; var e = 0.0
            for (i in 0 until WIN) { re[i] = x[o + i] * hann[i]; im[i] = 0f; e += x[o + i].toDouble() * x[o + i] }
            rms[fr] = sqrt(e / WIN)
            FFTUtils.compute(re, im)
            var sMag = 0.0; var sfMag = 0.0; var sLn = 0.0; var sLin = 0.0
            for (k in 1 until hiBin) {
                val mag = sqrt((re[k] * re[k] + im[k] * im[k]).toDouble()); val hz = k * binHz
                sMag += mag; sfMag += hz * mag; sLn += ln(mag + EPS)
                sLin += (hz - fMean) * ln(mag + EPS)               // covariance term for tilt slope
            }
            cen[fr] = if (sMag > EPS) sfMag / sMag else 0.0
            tilt[fr] = if (fVar > EPS) sLin / fVar else 0.0        // slope of log-mag vs freq: <0 pink, >0 blue
            flat[fr] = Math.exp(sLn / (hiBin - 1)) / (sMag / (hiBin - 1) + EPS)
        }

        // loudest event = expulsion onset
        var peak = 0; for (i in 1 until nf) if (rms[i] > rms[peak]) peak = i
        val pk = rms[peak].coerceAtLeast(EPS)
        val fps = sr.toDouble() / hop; val msPerFrame = 1000.0 / fps

        // attack (10%→peak) and decay (peak→10%)
        var a = peak; while (a > 0 && rms[a] > 0.1 * pk) a--
        var d = peak; while (d < nf - 1 && rms[d] > 0.1 * pk) d++
        val attackMs = (peak - a) * msPerFrame; val decayMs = (d - peak) * msPerFrame
        val peakWidthMs = rms.count { it > 0.5 * pk } * msPerFrame

        // pre/post windows around the burst
        val preLo = (peak - (0.5 * fps).toInt()).coerceAtLeast(0)   // ~500 ms before
        val postHi = (peak + (0.3 * fps).toInt()).coerceAtMost(nf - 1)   // ~300 ms after
        fun mean(arr: DoubleArray, lo: Int, hi: Int) = if (hi > lo) (lo until hi).sumOf { arr[it] } / (hi - lo) else 0.0
        fun slope(arr: DoubleArray, lo: Int, hi: Int): Double {
            val n = hi - lo; if (n < 3) return 0.0
            val mx = (n - 1) / 2.0; var sxy = 0.0; var sxx = 0.0; val my = mean(arr, lo, hi)
            for (i in 0 until n) { val dx = i - mx; sxy += dx * (arr[lo + i] - my); sxx += dx * dx }
            return if (sxx > EPS) sxy / sxx else 0.0
        }
        val centroidPre = mean(cen, preLo, peak); val centroidPost = mean(cen, peak, postHi)
        val centroidSlopePre = slope(cen, preLo, peak); val centroidSlopePost = slope(cen, peak, postHi)
        val tiltPre = mean(tilt, preLo, peak); val tiltPost = mean(tilt, peak, postHi)

        // gapDepth: how quiet the ~150 ms just before the burst gets relative to the burst (glottal closure)
        val gLo = (peak - (0.15 * fps).toInt()).coerceAtLeast(0)
        val gapMin = (gLo until peak).minOfOrNull { rms[it] } ?: pk
        val gapDepth = 1.0 - (gapMin / pk)                          // →1 if a true silent gap precedes the burst

        // inspirationRise: centroid rising (pink→blue) in the pre-burst window, scaled by broadband-ness
        val inspirationRise = centroidSlopePre * mean(flat, preLo, peak)

        // nPeaks: energy bursts above 40% peak separated by ≥150 ms dips (repeated coughs vs single swell)
        var nPeaks = 0; var i = 0; val sep = (0.15 * fps).toInt().coerceAtLeast(1)
        while (i < nf) { if (rms[i] > 0.4 * pk) { nPeaks++; i += sep } else i++ }

        return doubleArrayOf(attackMs, decayMs, peakWidthMs, centroidPre, centroidPost,
            centroidSlopePre, centroidSlopePost, tiltPre, tiltPost, gapDepth,
            inspirationRise, nPeaks.toDouble(), flat.average())
    }
}
