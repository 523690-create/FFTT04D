package com.example.FFTT04M.desktop.fractionation

import com.example.FFTT04M.desktop.cough.CoughDsp
import com.example.FFTT04M.desktop.cough.MfccExtractor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Method 7 — Feature Cluster Boundaries.
 * Pure-Kotlin AUD approximation: MFCC-13 + log-energy + ZCR per frame → z-score normalize →
 * k-means clustering → boundary wherever cluster assignment changes.
 * Merges segments shorter than minSegmentMs after assignment.
 */
class FeatureClusterBoundaries(
    private val k: Int = 32,
    private val maxIter: Int = 100,
    private val frameMs: Double = 25.0,
    private val hopMs: Double = 10.0,
    private val minSegmentMs: Int = 80,
) : Fractionator {

    override val name = "Feature Cluster Boundaries"

    private val mfcc = MfccExtractor(numCoeffs = 13, winMs = 25.0, hopMs = 10.0)

    override fun fractionate(x: FloatArray, sr: Int): List<Segment> {
        if (x.isEmpty()) return emptyList()
        val win = max(8, (frameMs / 1000 * sr).roundToInt())
        val hop = max(1, (hopMs / 1000 * sr).roundToInt())
        val minSegFrames = max(1, (minSegmentMs / hopMs).roundToInt())

        val numFrames = max(0, (x.size - win) / hop + 1)
        if (numFrames < k) return listOf(Segment(0, (x.size.toDouble() / sr * 1000).roundToInt(), clusterId = 0))

        // Per-frame features: 13 MFCCs + log-energy + ZCR = 15 dims
        val dims = 15
        val features = Array(numFrames) { f ->
            val start = f * hop; val end = (start + win).coerceAtMost(x.size)
            val slice = FloatArray(end - start) { x[start + it] }
            val mf = mfcc.extract(x, start, end, sr)
            var energy = 0.0; for (s in slice) energy += s.toDouble() * s
            val logE = if (energy > 1e-12) kotlin.math.log10(energy / slice.size) else -6.0
            var zc = 0; for (i in 1 until slice.size) if ((slice[i - 1] >= 0) != (slice[i] >= 0)) zc++
            DoubleArray(dims) { d -> when { d < 13 -> mf.mean[d]; d == 13 -> logE; else -> zc.toDouble() / slice.size } }
        }

        // Z-score normalize each dimension
        val mean = DoubleArray(dims); val std = DoubleArray(dims)
        for (f in features) for (d in 0 until dims) mean[d] += f[d]
        for (d in 0 until dims) mean[d] /= numFrames
        for (f in features) for (d in 0 until dims) { val delta = f[d] - mean[d]; std[d] += delta * delta }
        for (d in 0 until dims) std[d] = sqrt(std[d] / numFrames).coerceAtLeast(1e-8)
        for (f in features) for (d in 0 until dims) f[d] = (f[d] - mean[d]) / std[d]

        // K-means (k-means++ init)
        val clusterK = k.coerceAtMost(numFrames)
        val centroids = Array(clusterK) { DoubleArray(dims) }
        // Init: pick first centroid randomly, then by distance^2 weighting
        val rng = java.util.Random(42)
        centroids[0] = features[rng.nextInt(numFrames)].copyOf()
        for (c in 1 until clusterK) {
            val dists = DoubleArray(numFrames) { f ->
                var minD = Double.MAX_VALUE
                for (cc in 0 until c) {
                    var d = 0.0; for (dim in 0 until dims) { val delta = features[f][dim] - centroids[cc][dim]; d += delta * delta }
                    if (d < minD) minD = d
                }
                minD
            }
            val total = dists.sum()
            var r = rng.nextDouble() * total; var idx = 0
            while (idx < numFrames - 1 && r > dists[idx]) { r -= dists[idx]; idx++ }
            centroids[c] = features[idx].copyOf()
        }

        // Lloyd iterations
        val labels = IntArray(numFrames)
        repeat(maxIter) {
            // Assign
            var changed = false
            for (f in 0 until numFrames) {
                var bestC = 0; var bestD = Double.MAX_VALUE
                for (c in 0 until clusterK) {
                    var d = 0.0; for (dim in 0 until dims) { val delta = features[f][dim] - centroids[c][dim]; d += delta * delta }
                    if (d < bestD) { bestD = d; bestC = c }
                }
                if (labels[f] != bestC) { labels[f] = bestC; changed = true }
            }
            if (!changed) return@repeat
            // Update centroids
            val sums = Array(clusterK) { DoubleArray(dims) }; val counts = IntArray(clusterK)
            for (f in 0 until numFrames) { val c = labels[f]; for (d in 0 until dims) sums[c][d] += features[f][d]; counts[c]++ }
            for (c in 0 until clusterK) if (counts[c] > 0) for (d in 0 until dims) centroids[c][d] = sums[c][d] / counts[c]
        }

        // Segment on cluster transitions, merging short runs
        val segments = mutableListOf<Segment>()
        var segStart = 0; var segCluster = labels[0]
        for (f in 1..numFrames) {
            val curCluster = if (f < numFrames) labels[f] else -1
            if (curCluster != segCluster || f == numFrames) {
                val len = f - segStart
                if (len >= minSegFrames || segments.isEmpty()) {
                    val startMs = (segStart * hop.toDouble() / sr * 1000).roundToInt()
                    val endSample = (f * hop + win).coerceAtMost(x.size)
                    val endMs = (endSample.toDouble() / sr * 1000).roundToInt()
                    segments.add(Segment(startMs, endMs, clusterId = segCluster))
                } else {
                    // Merge short segment into the previous
                    val prev = segments.removeLast()
                    val endSample = (f * hop + win).coerceAtMost(x.size)
                    val endMs = (endSample.toDouble() / sr * 1000).roundToInt()
                    segments.add(Segment(prev.startMs, endMs, clusterId = prev.clusterId))
                }
                segStart = f; segCluster = curCluster
            }
        }
        return segments
    }
}
