package com.example.FFTT04M.desktop

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Shared multinomial logistic regression (softmax) with L2 + class-balanced loss, batch gradient
 * descent. Operates on raw DoubleArray feature vectors — used by both [HistogramClassifier]
 * (phoneme-letter histograms) and [WholeClipClassifier] (14-dim whole-clip features, 768-d HuBERT embeddings).
 *
 * The fixed step [lr] is only stable while lr·L < 2, where L = 0.5·(λmax + 1) + l2 bounds the curvature and λmax
 * is the top eigenvalue of the class-weighted second-moment matrix. At lr 0.5 that means λmax < ~7. Hand-built
 * features stay under that and take the plain loop, unchanged. z-scored HuBERT dimensions are strongly correlated
 * (λmax 90–160): there the plain loop never settled, its objective bouncing between 0.30 and 0.72 on coswara where
 * the true minimum is 0.096, with most predictions saturated at 0 or 1. Those fits now use Nesterov momentum at step
 * 1/L on the SAME objective. Audit and numbers: data/_softmax_audit_20261107/RESULTS.md (2026-11-07).
 */
object SoftmaxLR {

    private const val MAX_ACCELERATED_ITERS = 2000
    private const val STOP_CHECK_EVERY = 50
    private const val STOP_RELATIVE_CHANGE = 1e-6

    /** Returns (weights[class][feature], bias[class]). [sampleWeight] (optional, one entry per sample,
     *  same order as [xs]) multiplies the class-balanced weight — used for hard-negative upweighting.
     *  [lr] and [iters] apply to the plain loop only (see the class comment). Deterministic. */
    fun train(
        xs: List<DoubleArray>, ys: List<Int>, nClasses: Int,
        l2: Double = 0.02, lr: Double = 0.5, iters: Int = 500,
        sampleWeight: DoubleArray? = null,
    ): Pair<Array<DoubleArray>, DoubleArray> {
        val f = xs.firstOrNull()?.size ?: 0
        val c = nClasses
        val w = Array(c) { DoubleArray(f) }; val b = DoubleArray(c)
        if (f == 0 || xs.isEmpty()) return w to b
        val freq = IntArray(c); for (y in ys) if (y in 0 until c) freq[y]++
        val cw = DoubleArray(c) { if (freq[it] > 0) xs.size.toDouble() / (c * freq[it]) else 0.0 } // class-balanced
        val m = xs.size.toDouble()
        val sw = DoubleArray(xs.size) { n -> val y = ys[n]; if (y in 0 until c) cw[y] * (sampleWeight?.get(n) ?: 1.0) else 0.0 }

        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 16)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            /** Runs [body] over fixed index stripes and returns the per-stripe results in stripe order. */
            fun <T> stripes(body: (Int) -> T): List<T> =
                (0 until threads).map { t -> pool.submit(Callable { body(t) }) }.map { it.get() }

            val curvature = 0.5 * (topEigenvalue(xs, sw, m, f, threads, ::stripes) + 1.0) + l2
            if (lr * curvature < 2.0) {
                repeat(iters) {
                    val gw = Array(c) { DoubleArray(f) }; val gb = DoubleArray(c)
                    for (n in xs.indices) {
                        val yi = ys[n]; if (yi < 0 || yi >= c) continue
                        val x = xs[n]
                        val p = probs(w, b, x)
                        val weight = sw[n]
                        for (k in 0 until c) {
                            val err = (p[k] - if (k == yi) 1.0 else 0.0) * weight
                            gb[k] += err; for (j in 0 until f) gw[k][j] += err * x[j]
                        }
                    }
                    for (k in 0 until c) { b[k] -= lr * gb[k] / m; for (j in 0 until f) w[k][j] -= lr * (gw[k][j] / m + l2 * w[k][j]) }
                }
                return w to b
            }

            // Nesterov at step 1/L, full batch, same objective as the plain loop. Stripe sums are added in stripe
            // order, so the result does not depend on thread timing.
            val step = 1.0 / curvature
            val vw = Array(c) { DoubleArray(f) }; val vb = DoubleArray(c)
            val lw = Array(c) { DoubleArray(f) }; val lb = DoubleArray(c)
            var previous = Double.NaN
            for (iter in 1..MAX_ACCELERATED_ITERS) {
                for (k in 0 until c) { lb[k] = b[k] + 0.9 * vb[k]; for (j in 0 until f) lw[k][j] = w[k][j] + 0.9 * vw[k][j] }
                val parts = stripes { t ->
                    val gw = Array(c) { DoubleArray(f) }; val gb = DoubleArray(c)
                    var n = t
                    while (n < xs.size) {
                        val yi = ys[n]
                        if (yi in 0 until c) {
                            val x = xs[n]
                            val p = probs(lw, lb, x)
                            for (k in 0 until c) {
                                val err = (p[k] - if (k == yi) 1.0 else 0.0) * sw[n]
                                gb[k] += err; val g = gw[k]; for (j in 0 until f) g[j] += err * x[j]
                            }
                        }
                        n += threads
                    }
                    gw to gb
                }
                for (k in 0 until c) {
                    var db = 0.0; for (p in parts) db += p.second[k]
                    vb[k] = 0.9 * vb[k] - step * db / m; b[k] += vb[k]
                    for (j in 0 until f) {
                        var dw = 0.0; for (p in parts) dw += p.first[k][j]
                        vw[k][j] = 0.9 * vw[k][j] - step * (dw / m + l2 * lw[k][j]); w[k][j] += vw[k][j]
                    }
                }
                if (iter % STOP_CHECK_EVERY == 0) {
                    val current = objective(xs, ys, sw, w, b, l2, m, threads, ::stripes)
                    if (!previous.isNaN() && kotlin.math.abs(previous - current) < STOP_RELATIVE_CHANGE * maxOf(1.0, kotlin.math.abs(current))) break
                    previous = current
                }
            }
            return w to b
        } finally { pool.shutdown() }
    }

    /** λmax of Σ sw·x·xᵀ / m, by power iteration from a fixed start. */
    private fun topEigenvalue(
        xs: List<DoubleArray>, sw: DoubleArray, m: Double, f: Int, threads: Int,
        stripes: (body: (Int) -> DoubleArray) -> List<DoubleArray>,
    ): Double {
        var v = DoubleArray(f) { 1.0 / sqrt(f.toDouble()) }; var lam = 0.0
        repeat(40) {
            val current = v
            val parts = stripes { t ->
                val u = DoubleArray(f)
                var n = t
                while (n < xs.size) {
                    val x = xs[n]
                    var dot = 0.0; for (j in 0 until f) dot += current[j] * x[j]
                    val s = sw[n] * dot
                    for (j in 0 until f) u[j] += s * x[j]
                    n += threads
                }
                u
            }
            val u = DoubleArray(f); for (p in parts) for (j in 0 until f) u[j] += p[j]
            var norm = 0.0; for (j in 0 until f) { u[j] /= m; norm += u[j] * u[j] }
            lam = sqrt(norm)
            if (lam <= 0.0) return 0.0
            v = DoubleArray(f) { u[it] / lam }
        }
        return lam
    }

    private fun objective(
        xs: List<DoubleArray>, ys: List<Int>, sw: DoubleArray, w: Array<DoubleArray>, b: DoubleArray,
        l2: Double, m: Double, threads: Int, stripes: (body: (Int) -> Double) -> List<Double>,
    ): Double {
        val loss = stripes { t ->
            var s = 0.0
            var n = t
            while (n < xs.size) {
                val yi = ys[n]
                if (yi in w.indices) s -= sw[n] * ln(probs(w, b, xs[n])[yi].coerceAtLeast(1e-300))
                n += threads
            }
            s
        }.sum() / m
        var penalty = 0.0; for (row in w) for (v in row) penalty += v * v
        return loss + 0.5 * l2 * penalty
    }

    fun probs(w: Array<DoubleArray>, b: DoubleArray, x: DoubleArray): DoubleArray {
        val c = w.size
        val logits = DoubleArray(c) { k -> var s = b[k]; for (j in x.indices) s += w[k][j] * x[j]; s }
        val mx = logits.maxOrNull() ?: 0.0; var z = 0.0
        val p = DoubleArray(c) { k -> val e = exp(logits[k] - mx); z += e; e }
        for (k in 0 until c) p[k] /= z
        return p
    }
}
