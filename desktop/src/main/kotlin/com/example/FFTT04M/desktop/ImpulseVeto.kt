package com.example.FFTT04M.desktop

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Whole-clip veto for IMPULSIVE non-coughs (gun shots, barks, knocks, clicks) — the codebook
 * [CoughScore]'s worst false alarms (2026-10-03: gun_shot 42 %, knock/can-opening 40–45 %).
 *
 * A binary logistic head on the mean-pooled HuBERT clip embedding (768-d), trained as a SPECIALIST:
 * coughs vs impulsive clips only. It answers one question — "is this a non-human percussive sound?" —
 * and is used only to REMOVE a cough call:   cough  ⇔  coughScore > 0  AND  pCough ≥ [TAU].
 * It knows nothing about breathing or speech (it scores them cough-side); those stay the codebook's job.
 *
 * Why not put impulsive negatives in the codebook: measured 2026-10-04, it steals a cough's explosive-phase
 * windows (impulse FA gets WORSE at equal recall). Why not envelope-shape features (attack/decay/voiced
 * tail): measured 2026-10-05 — with clip-level features the model learns the RECORDING FORMAT (vetoed 98 %
 * of device coughs while looking perfect in-dataset); with event-shape features only it is too weak
 * (AUC 0.78).
 *
 * Measured for the deployed head (grouped out-of-fold scores — no clip is scored by a model that saw its
 * participant / source recording / device-day), coughScore > 0 alone → with the veto:
 *                          tau 0.1 (default)   tau 0.2
 *   impulsive false alarms   32.3 →  7.1 %       3.1 %    (gun_shot 47.7 → 9.7 / 3.7, dog_bark 24.1 → 3.1 / 0.6)
 *   coswara cough recall     78.2 → 77.9 %      77.7 %
 *   coughvid cough recall    56.2 → 56.1 %      56.0 %
 *   device cough recall      82.1 → 79.7 %      78.3 %    (device not-cough FA 10.4 → 8.4 / 7.7 %)
 * Breathing and speech false alarms are unchanged (15.4 / 5.1 %), as intended.
 * See data/_impulse_veto_20261005/RESULTS.md. Trained by [ImpulseVetoCli] (`:desktop:impulseVeto`, no GPU).
 */
object ImpulseVeto {
    /** -Dimpulse.tau=X — veto when pCough < τ. 0.1 = "veto only when the (class-balanced) head is ≥ 90 % sure
     *  it is impulsive"; raise it for fewer impulsive false alarms at the price of device-cough recall. */
    val TAU: Double = System.getProperty("impulse.tau")?.toDoubleOrNull() ?: 0.1
    const val MODEL_FILE = "impulse_veto.json"
    /** id → pCough for every clip with a cached embedding (out-of-fold for the clips the head trained on). */
    const val SCORES_FILE = "impulse_veto_scores.json"

    class Model(val mean: DoubleArray, val std: DoubleArray, val w: DoubleArray, val b: Double) {
        /** P(cough rather than impulsive noise) for a mean-pooled HuBERT clip embedding. */
        fun pCough(emb: FloatArray): Double {
            var s = b; for (j in w.indices) s += w[j] * (emb[j] - mean[j]) / std[j]
            return 1.0 / (1.0 + exp(-s))
        }
        fun pCough(emb: DoubleArray): Double {
            var s = b; for (j in w.indices) s += w[j] * (emb[j] - mean[j]) / std[j]
            return 1.0 / (1.0 + exp(-s))
        }
    }

    fun vetoed(pCough: Double?): Boolean = pCough != null && pCough < TAU

    /** The deployed model, or null when none has been trained (then nothing is ever vetoed). */
    val model: Model? by lazy { load(File(Workspace.dir("codebooks"), MODEL_FILE)) }

    fun load(f: File): Model? = if (!f.isFile) null else try {
        val o = JsonParser.parseString(f.readText()).asJsonObject
        Model(o.getAsJsonArray("mean").map { it.asDouble }.toDoubleArray(),
            o.getAsJsonArray("std").map { it.asDouble }.toDoubleArray(),
            o.getAsJsonArray("w").map { it.asDouble }.toDoubleArray(), o.get("b").asDouble)
    } catch (e: Exception) { System.err.println("impulse veto load ${f.name}: ${e.message}"); null }

    fun save(m: Model, f: File, info: Map<String, Any?> = emptyMap()) {
        f.writeText(Gson().toJson(mapOf("mean" to m.mean, "std" to m.std, "w" to m.w, "b" to m.b) + info))
    }

    /** Sidecar scores for clips whose decode record predates the `impulseP` field (the grid's fallback). */
    object Scores {
        @Volatile private var cache: Map<String, Double>? = null
        fun reload() { cache = null }
        fun get(id: String): Double? = (cache ?: read().also { cache = it })[id]
        private fun read(): Map<String, Double> {
            val f = File(Workspace.dir("codebooks"), SCORES_FILE)
            if (!f.isFile) return emptyMap()
            return try {
                JsonParser.parseString(f.readText()).asJsonObject.entrySet().associate { it.key to it.value.asDouble }
            } catch (e: Exception) { System.err.println("impulse veto scores: ${e.message}"); emptyMap() }
        }
    }

    /** The `clipemb_*.bin` cache format written by UnsupervisedCluster / DeviceHubertEvalCli. */
    fun loadEmb(f: File): LinkedHashMap<String, FloatArray> {
        val m = LinkedHashMap<String, FloatArray>()
        if (!f.isFile) return m
        DataInputStream(f.inputStream().buffered(1 shl 20)).use { dis ->
            while (true) {
                val id = try { dis.readUTF() } catch (e: EOFException) { break }
                val n = dis.readInt(); m[id] = FloatArray(n) { dis.readFloat() }
            }
        }
        return m
    }

    /**
     * Class-balanced L2 logistic regression, Nesterov momentum, full batch (sums run in parallel).
     * [weight] multiplies a sample's class-balanced weight (device coughs are few; see ImpulseVetoCli).
     *
     * The step is 1/L, L = Lipschitz bound of the gradient (0.25·λmax of the weighted second-moment matrix,
     * by power iteration, + l2). This matters: HuBERT dimensions are strongly correlated (λmax in the tens),
     * and a fixed step of 1.0 — the first cut — overshoots until every prediction SATURATES at 0 or 1. That
     * junk optimum still separates the training corpora (holdout AUC 0.97) but scores out-of-domain device
     * clips as a coin flip between "certainly cough" and "certainly impulse". Deterministic.
     */
    fun train(xs: List<FloatArray>, ys: BooleanArray, weight: DoubleArray? = null,
              l2: Double = 1e-2, iters: Int = 500): Model {
        val n = xs.size; val d = xs[0].size
        val mean = DoubleArray(d); val std = DoubleArray(d)
        for (x in xs) for (j in 0 until d) mean[j] += x[j]
        for (j in 0 until d) mean[j] /= n
        for (x in xs) for (j in 0 until d) { val e = x[j] - mean[j]; std[j] += e * e }
        for (j in 0 until d) std[j] = sqrt(std[j] / n) + 1e-6
        val z = Array(n) { i -> FloatArray(d) { j -> ((xs[i][j] - mean[j]) / std[j]).toFloat() } }
        var massPos = 0.0; var massNeg = 0.0
        for (i in 0 until n) { val m = weight?.get(i) ?: 1.0; if (ys[i]) massPos += m else massNeg += m }
        val total = massPos + massNeg
        val sw = DoubleArray(n) { i -> (weight?.get(i) ?: 1.0) * 0.5 / ((if (ys[i]) massPos else massNeg) / total) }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 16)
        val pool = Executors.newFixedThreadPool(threads)
        /** Σ_i c_i·z_i (first d entries) and Σ_i c_i (last), where c_i = f(i, z_i·v + bias). */
        fun weightedSum(v: DoubleArray, bias: Double, f: (Int, Double) -> Double): DoubleArray {
            val parts = (0 until threads).map { t ->
                pool.submit(Callable {
                    val g = DoubleArray(d + 1)
                    var i = t
                    while (i < n) {
                        val zi = z[i]; var dot = bias
                        for (j in 0 until d) dot += v[j] * zi[j]
                        val c = f(i, dot)
                        for (j in 0 until d) g[j] += c * zi[j]
                        g[d] += c; i += threads
                    }
                    g
                })
            }.map { it.get() }
            val out = DoubleArray(d + 1)
            for (g in parts) for (j in 0..d) out[j] += g[j]
            return out
        }
        val w = DoubleArray(d); val vw = DoubleArray(d); var b = 0.0; var vb = 0.0
        try {
            var v = DoubleArray(d) { 1.0 / sqrt(d.toDouble()) }; var lam = 1.0
            repeat(30) {                                          // power iteration on Zᵀ·diag(sw)·Z / total
                val u = weightedSum(v, 0.0) { i, dot -> sw[i] * dot }
                var nrm = 0.0; for (j in 0 until d) { u[j] /= total; nrm += u[j] * u[j] }
                lam = sqrt(nrm).coerceAtLeast(1e-9); v = DoubleArray(d) { u[it] / lam }
            }
            val lr = 1.0 / (0.25 * (lam + 1.0) + l2)              // +1: the bias column
            repeat(iters) {
                val wl = DoubleArray(d) { w[it] + 0.9 * vw[it] }; val bl = b + 0.9 * vb     // look-ahead point
                val g = weightedSum(wl, bl) { i, dot -> (1.0 / (1.0 + exp(-dot)) - (if (ys[i]) 1.0 else 0.0)) * sw[i] }
                for (j in 0 until d) { vw[j] = 0.9 * vw[j] - lr * (g[j] / total + l2 * wl[j]); w[j] += vw[j] }
                vb = 0.9 * vb - lr * g[d] / total; b += vb
            }
        } finally { pool.shutdown() }
        return Model(mean, std, w, b)
    }
}
