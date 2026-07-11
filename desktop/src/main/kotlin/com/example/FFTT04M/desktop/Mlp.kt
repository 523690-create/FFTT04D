package com.example.FFTT04M.desktop

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Small single-hidden-layer ReLU MLP (softmax output), full-batch gradient descent, data-parallel across
 * cores. [WholeClipClassifier]/[SoftmaxLR] are strictly LINEAR — this exists to test whether a residual a
 * linear model can't clear is a genuine information ceiling or just a linear-capacity ceiling (e.g. the
 * frozen-HuBERT-embedding cough-vs-breath boundary in [BreathSpecCli], where a handful of hard-negative
 * breaths survive every linear reweighting attempt).
 */
object Mlp {

    class Model(
        val mean: DoubleArray, val std: DoubleArray,
        val w1: Array<DoubleArray>, val b1: DoubleArray, val w2: Array<DoubleArray>, val b2: DoubleArray,
        val classes: List<String>,
    ) {
        private fun z(x: DoubleArray) = DoubleArray(x.size) { (x[it] - mean[it]) / std[it] }

        /** label, probability. */
        fun predict(x0: DoubleArray): Pair<String, Double> {
            val x = z(x0)
            val h = DoubleArray(b1.size) { j -> var s = b1[j]; val w = w1[j]; for (k in x.indices) s += w[k] * x[k]; if (s > 0) s else 0.0 }
            val logits = DoubleArray(b2.size) { k -> var s = b2[k]; val w = w2[k]; for (j in h.indices) s += w[j] * h[j]; s }
            val mx = logits.max(); var sum = 0.0
            val p = DoubleArray(logits.size) { val e = exp(logits[it] - mx); sum += e; e }
            for (i in p.indices) p[i] /= sum
            val best = p.indices.maxByOrNull { p[it] } ?: 0
            return classes[best] to p[best]
        }
    }

    /** [sampleWeight] (optional, per-sample, same order as [samples]) — hard-negative upweighting.
     *  [initFrom] (optional) — warm-start weights from an already-trained [Model] (e.g. a coswara-trained
     *  model as a transfer-learning initialization for device fine-tuning) instead of random init. Only
     *  applied when its hidden/input/output dims match this call's; falls back to random init otherwise.
     *  The normalizer (mean/std) is always re-fit on THIS call's samples, since the two domains'
     *  raw-feature distributions differ even when the learned weights transfer. */
    fun train(
        samples: List<Pair<DoubleArray, String>>, classes: List<String>,
        hidden: Int = 32, iters: Int = 300, sampleWeight: DoubleArray? = null,
        initFrom: Model? = null,
    ): Model {
        val d = samples.first().first.size
        val mean = DoubleArray(d); val std = DoubleArray(d)
        for ((x, _) in samples) for (i in 0 until d) mean[i] += x[i]
        for (i in 0 until d) mean[i] /= samples.size
        for ((x, _) in samples) for (i in 0 until d) { val e = x[i] - mean[i]; std[i] += e * e }
        for (i in 0 until d) std[i] = sqrt(std[i] / samples.size).coerceAtLeast(1e-9)
        val xs = samples.map { (x, _) -> DoubleArray(d) { (x[it] - mean[it]) / std[it] } }
        val ys = samples.map { classes.indexOf(it.second) }
        val nc = classes.size
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val rnd = java.util.Random(42)
        val canWarmStart = initFrom != null && initFrom.w1.size == hidden && initFrom.w1[0].size == d && initFrom.w2.size == nc
        val w1 = if (canWarmStart) Array(hidden) { initFrom!!.w1[it].copyOf() } else Array(hidden) { DoubleArray(d) { rnd.nextGaussian() * sqrt(2.0 / d) } }
        val b1 = if (canWarmStart) initFrom!!.b1.copyOf() else DoubleArray(hidden)
        val w2 = if (canWarmStart) Array(nc) { initFrom!!.w2[it].copyOf() } else Array(nc) { DoubleArray(hidden) { rnd.nextGaussian() * sqrt(2.0 / hidden) } }
        val b2 = if (canWarmStart) initFrom!!.b2.copyOf() else DoubleArray(nc)
        val freq = IntArray(nc); for (y in ys) if (y in 0 until nc) freq[y]++
        val cw = DoubleArray(nc) { if (freq[it] > 0) xs.size.toDouble() / (nc * freq[it]) else 0.0 }
        val m = xs.size.toDouble(); val lr = 0.1; val l2 = 1e-4

        class Grad {
            val gW1 = Array(hidden) { DoubleArray(d) }; val gb1 = DoubleArray(hidden)
            val gW2 = Array(nc) { DoubleArray(hidden) }; val gb2 = DoubleArray(nc)
            fun zero() { for (r in gW1) java.util.Arrays.fill(r, 0.0); java.util.Arrays.fill(gb1, 0.0); for (r in gW2) java.util.Arrays.fill(r, 0.0); java.util.Arrays.fill(gb2, 0.0) }
        }
        val chunks = xs.indices.chunked(((xs.size + cores - 1) / cores).coerceAtLeast(1))
        val accs = chunks.map { Grad() }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(cores)
        try {
            repeat(iters) {
                chunks.indices.map { c -> pool.submit {
                    val g = accs[c]; g.zero()
                    for (n in chunks[c]) {
                        val x = xs[n]; val yi = ys[n]; val wt = cw[yi] * (sampleWeight?.get(n) ?: 1.0)
                        val h = DoubleArray(hidden); for (j in 0 until hidden) { var s = b1[j]; val w = w1[j]; for (k in 0 until d) s += w[k] * x[k]; h[j] = if (s > 0) s else 0.0 }
                        val lg = DoubleArray(nc) { k -> var s = b2[k]; val w = w2[k]; for (j in 0 until hidden) s += w[j] * h[j]; s }
                        val mx = lg.max(); var sum = 0.0; val p = DoubleArray(nc) { val e = exp(lg[it] - mx); sum += e; e }; for (k in 0 until nc) p[k] /= sum
                        val dl = DoubleArray(nc) { (p[it] - if (it == yi) 1.0 else 0.0) * wt }
                        for (k in 0 until nc) { g.gb2[k] += dl[k]; val gg = g.gW2[k]; for (j in 0 until hidden) gg[j] += dl[k] * h[j] }
                        val dh = DoubleArray(hidden); for (j in 0 until hidden) { var s = 0.0; for (k in 0 until nc) s += w2[k][j] * dl[k]; dh[j] = if (h[j] > 0) s else 0.0 }
                        for (j in 0 until hidden) { g.gb1[j] += dh[j]; val gg = g.gW1[j]; for (k in 0 until d) gg[k] += dh[j] * x[k] }
                    }
                } }.forEach { it.get() }
                for (j in 0 until hidden) {
                    var db = 0.0; for (a in accs) db += a.gb1[j]; b1[j] -= lr * db / m
                    val w = w1[j]; for (k in 0 until d) { var dw = 0.0; for (a in accs) dw += a.gW1[j][k]; w[k] -= lr * (dw / m + l2 * w[k]) }
                }
                for (k in 0 until nc) {
                    var db = 0.0; for (a in accs) db += a.gb2[k]; b2[k] -= lr * db / m
                    val w = w2[k]; for (j in 0 until hidden) { var dw = 0.0; for (a in accs) dw += a.gW2[k][j]; w[j] -= lr * (dw / m + l2 * w[j]) }
                }
            }
        } finally { pool.shutdown() }
        return Model(mean, std, w1, b1, w2, b2, classes)
    }

    /** Serialize a trained [Model] (mirrors [WholeClipClassifier.save]) so a winning MLP (e.g. the
     *  breath-specificity HuBERT-MLP) doesn't need retraining from scratch every run. */
    fun save(model: Model, out: File) {
        out.writeText(GsonBuilder().create().toJson(mapOf(
            "classes" to model.classes, "mean" to model.mean, "std" to model.std,
            "w1" to model.w1, "b1" to model.b1, "w2" to model.w2, "b2" to model.b2)))
    }

    fun load(f: File): Model? = try {
        val o = JsonParser.parseString(f.readText()).asJsonObject
        Model(
            o.getAsJsonArray("mean").map { it.asDouble }.toDoubleArray(),
            o.getAsJsonArray("std").map { it.asDouble }.toDoubleArray(),
            o.getAsJsonArray("w1").map { row -> row.asJsonArray.map { it.asDouble }.toDoubleArray() }.toTypedArray(),
            o.getAsJsonArray("b1").map { it.asDouble }.toDoubleArray(),
            o.getAsJsonArray("w2").map { row -> row.asJsonArray.map { it.asDouble }.toDoubleArray() }.toTypedArray(),
            o.getAsJsonArray("b2").map { it.asDouble }.toDoubleArray(),
            o.getAsJsonArray("classes").map { it.asString })
    } catch (e: Exception) { System.err.println("mlp load: ${e.message}"); null }
}
