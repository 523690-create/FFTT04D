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

    // Acoustic features only. Demographics (age/gender) are NOT cough features — they're emitted as
    // separate descriptive columns by tensorCsv, not mixed into the feature vector.
    val featureNames: List<String> =
        listOf("ridge_curv", "ridge_slope", "ridge_cfreq", "duration_s",
                "ridge_energy", "ridge_bw", "q_ratio", "fmax_hz") +
        (0 until 13).map { "mfcc_$it" }

    /** Provenance/demographics parsed from a recording id, kept OUT of the feature vector and the
     *  label so the CSV's first column isn't polluted with age/sex (user request). */
    data class Meta(
        val clipId: String, val source: String, val soundType: String,
        val age: String, val gender: String, val health: String,
    )

    data class Row(val label: String, val isCough: Boolean, val raw: DoubleArray, val meta: Meta,
                   val ridge: com.example.FFTT04M.desktop.cough.RidgeFeatures = com.example.FFTT04M.desktop.cough.RidgeFeatures.NONE)

    private val GENDERS = setOf("male", "female", "other", "m", "f", "man", "woman")
    private val AGE_RE = Regex("^a(\\d{1,3})$")

    /** Parse the ALLDATA filename id `source__origId__sound__(cough|noncough)__health__a<age>__<gender>__country`.
     *  Optional fields are detected by pattern (age = `a\d+`, gender = known word) so positions can shift.
     *  Non-dataset (transferred) clips with no `__` just yield a clean id and blank demographics. */
    private fun parseMeta(recId: String): Meta {
        val parts = recId.split("__")
        val source = parts.getOrElse(0) { "" }
        val soundType = parts.getOrElse(2) { "" }
        var age = ""; var gender = ""
        for (p in parts) {
            AGE_RE.find(p)?.let { age = it.groupValues[1] }
            if (p.lowercase() in GENDERS) gender = p.lowercase()
        }
        val flag = parts.indexOfFirst { it == "cough" || it == "noncough" }
        val health = if (flag >= 0 && flag + 1 < parts.size) parts[flag + 1] else ""
        // Stable, demographics-free id: source + original id (or the whole id if it isn't ALLDATA-formatted).
        val clipId = if (parts.size >= 2) listOf(source, parts[1]).filter { it.isNotBlank() }.joinToString("__")
                     else recId
        return Meta(clipId, source, soundType, age, gender, health)
    }

    data class Tensor(
        val rows: List<Row>,
        val standardized: List<DoubleArray>,
        val mean: DoubleArray,
        val std: DoubleArray,
    ) {
        val n get() = rows.size
        val dim get() = featureNames.size
    }

    /** Per-event acoustic feature vector; missing MFCC defaults to 0 (z-score handles scale). */
    private fun eventVector(e: CoughEvent): DoubleArray {
        val base = e.featureVector()                 // 8 DSP dims
        val mfccMean = e.mfcc?.mean ?: DoubleArray(0)
        val mfcc13 = DoubleArray(13) { if (it < mfccMean.size) mfccMean[it] else 0.0 }
        return base + mfcc13
    }

    fun buildTensor(results: List<ParallelCoughAnalyzer.ClipResult>): Tensor {
        val rows = ArrayList<Row>()
        for (r in results) {
            val a = r.analysis ?: continue
            val meta = parseMeta(r.recording.id)
            for (e in a.events) {
                rows.add(Row(
                    label = "${meta.clipId}#${e.index}",
                    isCough = e.speech.isLikelyCough,
                    raw = eventVector(e),
                    meta = meta,
                    ridge = e.ridge,
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

    /** Standardized tensor as CSV for external tools / training. Demographics/provenance are now
     *  their own columns (clip_id, source, sound_type, age, gender, health) so the first column is a
     *  clean id and `is_cough` is the lone target — no age/sex buried in the label. */
    fun tensorCsv(t: Tensor, standardized: Boolean = true): String {
        val sb = StringBuilder()
        sb.append("clip_id,source,sound_type,age,gender,health,is_cough,")
            .append(featureNames.joinToString(","))
            .append(",ridge_dur_s,ridge_fstart_hz,ridge_fpeak_hz,ridge_fend_hz,ridge_vertex_t").append('\n')
        for (idx in t.rows.indices) {
            val row = t.rows[idx]
            val m = row.meta
            val vec = if (standardized) t.standardized[idx] else row.raw
            sb.append(q(row.label)).append(',')
                .append(q(m.source)).append(',').append(q(m.soundType)).append(',')
                .append(q(m.age)).append(',').append(q(m.gender)).append(',').append(q(m.health)).append(',')
            sb.append(if (row.isCough) 1 else 0).append(',')
            sb.append(vec.joinToString(",") { fmt(it) })
            val rg = row.ridge   // located-squiggle params in RAW Hz/s (not standardised) for distribution analysis
            sb.append(',').append(fmt(rg.ridgeDurationSec)).append(',').append(fmt(rg.startFreqHz)).append(',')
                .append(fmt(rg.peakFreqHz)).append(',').append(fmt(rg.endFreqHz)).append(',').append(fmt(rg.vertexTimeSec)).append('\n')
        }
        return sb.toString()
    }

    /** CSV-quote a string field (RFC4180). */
    private fun q(s: String): String = '"' + s.replace("\"", "\"\"") + '"'

    private fun fmt(v: Double): String =
        if (v.isNaN() || v.isInfinite()) "0" else String.format(java.util.Locale.US, "%.6g", v)
}
