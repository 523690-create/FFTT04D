package com.example.FFTT04M.desktop

import com.google.gson.GsonBuilder
import java.io.File

/**
 * Builds the compact `cough_ref.json` the M app bundles for on-device "cloud match": a stratified
 * subset of the standardized cough tensor, PLUS the standardization mean/std (which the plain
 * tensor CSV drops) so a device clip's raw vector can be projected into the same z-scored space.
 *
 * Stratified by `source|sound_type|health` (≤[maxPerBucket] each) for class coverage, then capped to
 * [cap] by even striding. Each ref carries its labels + the 21-D z-scored vector. ~1 MB at cap=4000.
 */
object MatchRefExporter {

    private data class Ref(
        val src: String, val sound: String, val health: String,
        val cough: Boolean, val v: DoubleArray,
    )
    private data class RefFile(
        val dim: Int, val featureNames: List<String>,
        val mean: DoubleArray, val std: DoubleArray, val refs: List<Ref>,
    )

    private fun build(t: MetaAnalyzer.Tensor, maxPerBucket: Int = 30, cap: Int = 4000): Pair<RefFile, Map<String, Int>> {
        val buckets = HashMap<String, Int>()
        val picked = ArrayList<Ref>()
        for (idx in t.rows.indices) {
            val row = t.rows[idx]; val m = row.meta
            val key = "${m.source}|${m.soundType}|${m.health}"
            val n = buckets.getOrDefault(key, 0)
            if (n >= maxPerBucket) continue
            buckets[key] = n + 1
            picked.add(Ref(m.source, m.soundType, m.health, row.isCough, t.standardized[idx]))
        }
        val refs = if (picked.size <= cap) picked else {
            val stride = picked.size.toDouble() / cap
            (0 until cap).map { picked[(it * stride).toInt()] }
        }
        return RefFile(t.dim, MetaAnalyzer.featureNames, t.mean, t.std, refs) to buckets
    }

    /** Write the reference JSON; returns (refCount, bucketCount). */
    fun write(t: MetaAnalyzer.Tensor, out: File): Pair<Int, Int> {
        val (rf, buckets) = build(t)
        out.writeText(GsonBuilder().create().toJson(rf))
        return rf.refs.size to buckets.size
    }
}
