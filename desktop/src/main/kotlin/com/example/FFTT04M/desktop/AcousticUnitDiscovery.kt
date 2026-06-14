package com.example.FFTT04M.desktop

import com.example.FFTT04M.desktop.cough.CoughSimilarity
import com.google.gson.GsonBuilder
import java.io.File
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Acoustic Unit Discovery — the "hard" desktop step that turns the whole sound database into ONE
 * joint codebook of acoustic units ("phonemes"). Each clip is featurised with the portable
 * [WholeClipFeatures] (14-dim, byte-identical on the M devices), z-score standardized, then k-means
 * clustered into K units. Each unit is tagged with a coarse [RespiratoryTaxonomy.Group]
 * (RESPIRATORY / SPEECH / NOISE) by the majority of its labelled members, plus a fine source name.
 *
 * The exported [Codebook] (feature names + standardization mean/std + centroids + per-unit group) is
 * the artifact the M apps load to decode ambient sound: extract the same vector → standardize with
 * these stats → nearest centroid → keep the token if its group is RESPIRATORY, else reject.
 *
 * Training is desktop-only and reproducible (fixed RNG seed). Run it AFTER the databases are
 * imported; this class only builds the codebook, it does not modify capture behaviour.
 */
object AcousticUnitDiscovery {

    /** Codebook artifact (gson-serialized). Field names are the on-device contract — keep stable. */
    data class Codebook(
        val version: Int,
        val feature_names: List<String>,
        val standardization_mean: DoubleArray,
        val standardization_std: DoubleArray,
        val units: List<CodeUnit>,
        val event_count: Int,
    )

    data class CodeUnit(
        val id: String,            // e.g. "cough_03"
        val group: String,         // RESPIRATORY | SPEECH | NOISE | UNKNOWN
        val fine: String,          // dominant source label (cough, sneeze, breathing, speech, <noise>)
        val centroid: DoubleArray, // in standardized feature space
        val size: Int,
        val purity: Double,        // majority-label fraction among labelled members (0..1)
        val exemplar: String,      // file path of the member nearest the centroid (for listening)
    )

    private data class Row(val vec: DoubleArray, val group: RespiratoryTaxonomy.Group, val fine: String, val file: String)

    data class Result(val codebook: Codebook, val groupCounts: Map<String, Int>, val meanPurity: Double)

    /**
     * Discover a [Codebook] from [recordings]. Decodes + featurises in parallel, standardizes,
     * k-means-clusters into [k] units, tags groups by labelled majority.
     */
    fun discover(
        recordings: List<AudioRecording>,
        k: Int,
        workers: Int = Runtime.getRuntime().availableProcessors(),
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result {
        // 1) Parallel decode + WholeClipFeatures + taxonomy.
        val pool = Executors.newFixedThreadPool(workers)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val rows: List<Row> = try {
            recordings.map { rec ->
                pool.submit<Row?> {
                    val r = featurise(rec)
                    onProgress(done.incrementAndGet(), recordings.size)
                    r
                }
            }.mapNotNull { it.get() }
        } finally { pool.shutdown() }

        require(rows.size >= k) { "Need at least k=$k events; only ${rows.size} featurised." }

        // 2) Standardize (same z-score the device will apply, shipped in the codebook).
        val std = CoughSimilarity.standardize(rows.map { it.vec })
        val z = std.vectors

        // 3) k-means (k-means++ init, Lloyd iterations; fixed seed = reproducible codebook).
        val (centroids, assign) = kMeans(z, k, iters = 30, seed = 42L)

        // 4) Tag each cluster by labelled-majority group/fine; pick a centroid exemplar.
        val units = ArrayList<CodeUnit>(k)
        var puritySum = 0.0; var purityN = 0
        for (c in 0 until k) {
            val members = assign.indices.filter { assign[it] == c }
            if (members.isEmpty()) continue
            val labelled = members.filter { rows[it].group != RespiratoryTaxonomy.Group.UNKNOWN }
            val fineCounts = labelled.groupingBy { rows[it].fine }.eachCount()
            val topFine = fineCounts.maxByOrNull { it.value }?.key ?: "unknown"
            val group = labelled.map { rows[it].group }.groupingBy { it }.eachCount()
                .maxByOrNull { it.value }?.key ?: RespiratoryTaxonomy.Group.UNKNOWN
            val purity = if (labelled.isNotEmpty()) (fineCounts[topFine] ?: 0).toDouble() / labelled.size else 0.0
            if (labelled.isNotEmpty()) { puritySum += purity; purityN++ }
            val exemplar = members.minByOrNull { CoughSimilarity.euclidean(z[it], centroids[c]) }
                ?.let { rows[it].file } ?: ""
            units.add(CodeUnit("${topFine}_${"%02d".format(c)}", group.name, topFine,
                centroids[c], members.size, purity, exemplar))
        }

        val cb = Codebook(
            version = 1,
            feature_names = WholeClipFeatures.names,
            standardization_mean = std.mean,
            standardization_std = std.std,
            units = units.sortedBy { it.group + it.fine },
            event_count = rows.size,
        )
        val groupCounts = units.groupingBy { it.group }.fold(0) { acc, u -> acc + u.size }
        return Result(cb, groupCounts, if (purityN > 0) puritySum / purityN else 0.0)
    }

    private fun featurise(rec: AudioRecording): Row? {
        val pcm = try { AudioDecoder.decode(rec.audioFile) } catch (_: Throwable) { null } ?: return null
        if (pcm.isEmpty()) return null
        val vec = WholeClipFeatures.extract(pcm, rec.sampleRate)
        val label = RespiratoryTaxonomy.classify(rec.metadata)
        return Row(vec, label.group, label.fine, rec.audioFile.absolutePath)
    }

    /** Standard k-means: k-means++ seeding, Lloyd iterations. Returns (centroids, assignments). */
    private fun kMeans(data: List<DoubleArray>, k: Int, iters: Int, seed: Long): Pair<Array<DoubleArray>, IntArray> {
        val n = data.size; val dim = data[0].size
        val rng = Random(seed)
        // k-means++ init
        val centroids = Array(k) { DoubleArray(dim) }
        centroids[0] = data[rng.nextInt(n)].copyOf()
        val d2 = DoubleArray(n) { Double.MAX_VALUE }
        for (c in 1 until k) {
            var sum = 0.0
            for (i in 0 until n) {
                val dist = CoughSimilarity.euclidean(data[i], centroids[c - 1]).let { it * it }
                if (dist < d2[i]) d2[i] = dist
                sum += d2[i]
            }
            var target = rng.nextDouble() * sum; var pick = 0
            for (i in 0 until n) { target -= d2[i]; if (target <= 0) { pick = i; break } }
            centroids[c] = data[pick].copyOf()
        }
        val assign = IntArray(n)
        repeat(iters) {
            var moved = false
            for (i in 0 until n) {
                var best = 0; var bestD = Double.MAX_VALUE
                for (c in 0 until k) {
                    val d = CoughSimilarity.euclidean(data[i], centroids[c])
                    if (d < bestD) { bestD = d; best = c }
                }
                if (assign[i] != best) { assign[i] = best; moved = true }
            }
            val sums = Array(k) { DoubleArray(dim) }; val counts = IntArray(k)
            for (i in 0 until n) { val c = assign[i]; counts[c]++; val v = data[i]; for (j in 0 until dim) sums[c][j] += v[j] }
            for (c in 0 until k) if (counts[c] > 0) for (j in 0 until dim) centroids[c][j] = sums[c][j] / counts[c]
            if (!moved) return@repeat
        }
        return centroids to assign
    }

    fun toJson(cb: Codebook): String = GsonBuilder().setPrettyPrinting().create().toJson(cb)

    fun write(cb: Codebook, file: File) = file.writeText(toJson(cb))
}
