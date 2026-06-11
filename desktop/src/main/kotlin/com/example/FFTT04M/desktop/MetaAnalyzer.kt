package com.example.FFTT04M.desktop

import com.example.FFTT04M.desktop.cough.CoughEvent
import com.example.FFTT04M.desktop.cough.CoughSimilarity
import kotlin.math.sqrt

/**
 * Builds one big feature tensor over every detected cough event across all analysed clips — DSP
 * features (ridge parabola, FFT q-ratio/Fmax, duration/energy/bandwidth) + the 13 MFCC means +
 * numeric metadata (age) — then z-score-standardizes the columns and measures Euclidean distance
 * between individual cough representations (nearest neighbours, pairwise-distance stats, clusters).
 *
 * Reuses the homologous [CoughSimilarity] (same z-score + Euclidean code as the on-device app).
 */
object MetaAnalyzer {

    val featureNames: List<String> =
        listOf("ridge_curv", "ridge_slope", "ridge_cfreq", "duration_s",
                "ridge_energy", "ridge_bw", "q_ratio", "fmax_hz") +
        (0 until 13).map { "mfcc_$it" } +
        listOf("age")

    data class Row(val label: String, val isCough: Boolean, val raw: DoubleArray)

    data class Tensor(
        val rows: List<Row>,
        val standardized: List<DoubleArray>,
        val mean: DoubleArray,
        val std: DoubleArray,
    ) {
        val n get() = rows.size
        val dim get() = featureNames.size
    }

    /** Per-event feature vector; missing MFCC/age default to 0 (z-score handles scale). */
    private fun eventVector(e: CoughEvent, rec: AudioRecording): DoubleArray {
        val base = e.featureVector()                 // 8 DSP dims
        val mfccMean = e.mfcc?.mean ?: DoubleArray(0)
        val mfcc13 = DoubleArray(13) { if (it < mfccMean.size) mfccMean[it] else 0.0 }
        val age = (rec.metadata["age"] ?: rec.metadata["a"])?.toString()?.toDoubleOrNull() ?: 0.0
        return base + mfcc13 + doubleArrayOf(age)
    }

    fun buildTensor(results: List<ParallelCoughAnalyzer.ClipResult>): Tensor {
        val rows = ArrayList<Row>()
        for (r in results) {
            val a = r.analysis ?: continue
            for (e in a.events) {
                rows.add(Row(
                    label = "${r.recording.id}#${e.index}",
                    isCough = e.speech.isLikelyCough,
                    raw = eventVector(e, r.recording),
                ))
            }
        }
        if (rows.isEmpty()) return Tensor(rows, emptyList(), DoubleArray(featureNames.size), DoubleArray(featureNames.size))
        val z = CoughSimilarity.standardize(rows.map { it.raw })
        return Tensor(rows, z.vectors, z.mean, z.std)
    }

    data class Summary(
        val n: Int, val dim: Int, val coughCount: Int,
        val meanDist: Double, val minDist: Double, val maxDist: Double,
        val nnExamples: List<Triple<String, String, Double>>,   // (event, nearest event, distance)
        val pairwiseComputed: Boolean,
    )

    /**
     * Distance summary. Full pairwise is O(n²); above [maxPairwise] events we still compute each
     * event's nearest neighbour (also O(n²) but no n² matrix held in memory) and skip global stats.
     */
    fun analyze(t: Tensor, maxPairwise: Int = 3000, nnSamples: Int = 12): Summary {
        val z = t.standardized
        val n = z.size
        val coughCount = t.rows.count { it.isCough }
        if (n < 2) return Summary(n, t.dim, coughCount, 0.0, 0.0, 0.0, emptyList(), false)

        // Each event's nearest neighbour (for a sample of events, to show "most similar cough").
        val nn = ArrayList<Triple<String, String, Double>>()
        val step = (n / nnSamples).coerceAtLeast(1)
        var i = 0
        while (i < n && nn.size < nnSamples) {
            val (j, d) = CoughSimilarity.nearestNeighbor(i, z)
            if (j in 0 until n) nn.add(Triple(t.rows[i].label, t.rows[j].label, d))
            i += step
        }

        if (n > maxPairwise) {
            return Summary(n, t.dim, coughCount, 0.0, 0.0, 0.0, nn, false)
        }
        // Global pairwise distance stats.
        var sum = 0.0; var mn = Double.MAX_VALUE; var mx = 0.0; var cnt = 0L
        for (a in 0 until n) for (b in a + 1 until n) {
            val d = CoughSimilarity.euclidean(z[a], z[b])
            sum += d; cnt++; if (d < mn) mn = d; if (d > mx) mx = d
        }
        return Summary(n, t.dim, coughCount, if (cnt > 0) sum / cnt else 0.0, mn, mx, nn, true)
    }

    /** Standardized tensor as CSV (label,is_cough,<features...>) for external tools / training. */
    fun tensorCsv(t: Tensor, standardized: Boolean = true): String {
        val sb = StringBuilder()
        sb.append("label,is_cough,").append(featureNames.joinToString(",")).append('\n')
        for (idx in t.rows.indices) {
            val row = t.rows[idx]
            val vec = if (standardized) t.standardized[idx] else row.raw
            sb.append('"').append(row.label.replace("\"", "\"\"")).append('"').append(',')
            sb.append(if (row.isCough) 1 else 0).append(',')
            sb.append(vec.joinToString(",") { fmt(it) }).append('\n')
        }
        return sb.toString()
    }

    private fun fmt(v: Double): String =
        if (v.isNaN() || v.isInfinite()) "0" else String.format(java.util.Locale.US, "%.6g", v)
}
