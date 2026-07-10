package com.example.FFTT04M.desktop

import kotlin.math.exp

/**
 * Shared multinomial logistic regression (softmax) with L2 + class-balanced loss, batch gradient
 * descent. Operates on raw DoubleArray feature vectors — used by both [HistogramClassifier]
 * (phoneme-letter histograms) and [WholeClipClassifier] (14-dim whole-clip features).
 */
object SoftmaxLR {

    /** Returns (weights[class][feature], bias[class]). [sampleWeight] (optional, one entry per sample,
     *  same order as [xs]) multiplies the class-balanced weight — used for hard-negative upweighting. */
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
        repeat(iters) {
            val gw = Array(c) { DoubleArray(f) }; val gb = DoubleArray(c)
            for (n in xs.indices) {
                val yi = ys[n]; if (yi < 0 || yi >= c) continue
                val x = xs[n]
                val p = probs(w, b, x)
                val weight = cw[yi] * (sampleWeight?.get(n) ?: 1.0)
                for (k in 0 until c) {
                    val err = (p[k] - if (k == yi) 1.0 else 0.0) * weight
                    gb[k] += err; for (j in 0 until f) gw[k][j] += err * x[j]
                }
            }
            for (k in 0 until c) { b[k] -= lr * gb[k] / m; for (j in 0 until f) w[k][j] -= lr * (gw[k][j] / m + l2 * w[k][j]) }
        }
        return w to b
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
