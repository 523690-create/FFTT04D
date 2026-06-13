package com.example.FFTT04M.desktop

import com.example.FFTT04M.desktop.cough.CoughAnalyzer
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.math.sqrt

/**
 * Cloud meta-analysis: build a labeled per-recording vector pool from ALLDATA (one sound cloud +
 * multi-label qualifiers per recording — see [CloudMetaAnalyzer]), then measure the user's own
 * recordings against it by k-NN. Per-recording vector = [WholeClipFeatures] (14) + paroxysm block (8).
 * Also tags each recording with its [CoughClassifier] cough probability.
 */
object CloudAnalysis {

    val featureNames: List<String> = WholeClipFeatures.names + CloudMetaAnalyzer.paroxNames
    private const val SR = 44100
    private val workers = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    data class Rec(val id: String, val vec: FloatArray, val sound: String, val quals: Set<String>, val coughProb: Double)
    data class Model(val pool: List<Rec>, val mean: FloatArray, val std: FloatArray, val labelCounts: Map<String, Int>)
    data class Progress(val done: Int, val total: Int, val msg: String)
    data class Match(val sounds: List<Pair<String, Double>>, val quals: List<Pair<String, Double>>,
                     val neighbors: List<Pair<String, Double>>)

    /** Raw (unstandardized) per-recording vector. */
    fun vectorFor(pcm: FloatArray): DoubleArray =
        WholeClipFeatures.extract(pcm, SR) + CloudMetaAnalyzer.paroxysmFeatures(CoughAnalyzer().analyze(pcm, SR))

    /** Build the standardized labeled pool from ALLDATA (sampled up to [maxPool] recordings). */
    fun buildModel(allDataDir: File, maxPool: Int, cancel: AtomicBoolean, onProgress: (Progress) -> Unit): Model {
        val rows = CloudMetaAnalyzer.readMetadata(File(allDataDir, "metadata.csv"))
            .filter { File(allDataDir, it["wav"] ?: "").isFile }
        val sampled = if (rows.size <= maxPool) rows
        else (0 until maxPool).map { rows[(it.toLong() * rows.size / maxPool).toInt()] }
        val total = sampled.size
        onProgress(Progress(0, total, "Building clouds from $total recordings…"))

        val done = AtomicInteger(); val pool = ConcurrentLinkedQueue<Rec>()
        val pe = Executors.newFixedThreadPool(workers)
        try {
            sampled.map { r ->
                pe.submit {
                    if (!cancel.get()) {
                        val wav = File(allDataDir, r["wav"]!!)
                        val pcm = AudioDecoder.decode(wav)
                        if (pcm != null && pcm.isNotEmpty()) {
                            val v = vectorFor(pcm)
                            if (v.all { it.isFinite() }) {
                                val lab = CloudMetaAnalyzer.labelsFor(r)
                                pool.add(Rec(wav.nameWithoutExtension, FloatArray(v.size) { v[it].toFloat() },
                                    lab.soundCloud, lab.qualifiers, CoughClassifier.coughProb(pcm, SR)))
                            }
                        }
                        val dd = done.incrementAndGet()
                        if (dd % 50 == 0 || dd == total) onProgress(Progress(dd, total, "Clouds: $dd/$total"))
                    }
                }
            }.forEach { it.get() }
        } finally { pe.shutdown() }

        val list = pool.toList()
        val d = featureNames.size
        val mean = FloatArray(d); val std = FloatArray(d)
        for (j in 0 until d) { var s = 0.0; for (r in list) s += r.vec[j]; mean[j] = (s / list.size).toFloat() }
        for (j in 0 until d) { var s = 0.0; for (r in list) { val e = r.vec[j] - mean[j]; s += e * e }
            std[j] = sqrt(s / list.size).toFloat().coerceAtLeast(1e-6f) }
        val stdPool = list.map { r -> Rec(r.id, FloatArray(d) { (r.vec[it] - mean[it]) / std[it] }, r.sound, r.quals, r.coughProb) }
        val counts = HashMap<String, Int>()
        for (r in stdPool) { counts[r.sound] = (counts[r.sound] ?: 0) + 1; for (q in r.quals) counts[q] = (counts[q] ?: 0) + 1 }
        return Model(stdPool, mean, std, counts)
    }

    private fun std(model: Model, raw: DoubleArray) = FloatArray(raw.size) { ((raw[it] - model.mean[it]) / model.std[it]).toFloat() }
    private fun dist(a: FloatArray, b: FloatArray): Double { var s = 0.0; for (i in a.indices) { val e = a[i] - b[i]; s += e * e }; return sqrt(s) }

    /** k-NN vote (distance-weighted), keeping only labels with ≥ [minSize] support. */
    fun match(model: Model, rawVec: DoubleArray, k: Int = 5, minSize: Int = 20): Match {
        val q = std(model, rawVec)
        val near = model.pool.map { it to dist(q, it.vec) }.sortedBy { it.second }.take(k)
        val sv = HashMap<String, Double>(); val qv = HashMap<String, Double>()
        for ((r, dd) in near) {
            val w = 1.0 / (dd + 1e-6)
            sv[r.sound] = (sv[r.sound] ?: 0.0) + w
            for (ql in r.quals) qv[ql] = (qv[ql] ?: 0.0) + w
        }
        fun rank(m: Map<String, Double>) = m.entries.filter { (model.labelCounts[it.key] ?: 0) >= minSize }
            .sortedByDescending { it.value }.map { it.key to it.value }
        return Match(rank(sv), rank(qv), near.map { it.first.id to it.second })
    }

    // ---- 2D PCA scatter (pool coloured by sound cloud, extras as black ✕) ----
    fun pcaScatter(model: Model, extras: List<Pair<String, DoubleArray>>, out: File) {
        val d = featureNames.size
        val cov = Array(d) { DoubleArray(d) }
        for (r in model.pool) for (i in 0 until d) { val vi = r.vec[i]; for (j in i until d) cov[i][j] += vi * r.vec[j] }
        for (i in 0 until d) for (j in i until d) { cov[i][j] /= model.pool.size; cov[j][i] = cov[i][j] }
        val pc1 = powerIter(cov, null); val pc2 = powerIter(deflate(cov, pc1), null)
        fun proj(v: FloatArray): Pair<Double, Double> { var a = 0.0; var b = 0.0; for (i in 0 until d) { a += v[i] * pc1[i]; b += v[i] * pc2[i] }; return a to b }

        val sounds = model.pool.map { it.sound }.distinct()
        val palette = sounds.mapIndexed { i, s -> s to Color.getHSBColor(i.toFloat() / sounds.size.coerceAtLeast(1), 0.65f, 0.85f) }.toMap()
        val pts = model.pool.map { proj(it.vec) to it.sound }
        val xs = pts.map { it.first.first }; val ys = pts.map { it.first.second }
        val minX = xs.minOrNull()!!; val maxX = xs.maxOrNull()!!; val minY = ys.minOrNull()!!; val maxY = ys.maxOrNull()!!
        val W = 1000; val H = 760; val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB); val g = img.createGraphics()
        g.color = Color.WHITE; g.fillRect(0, 0, W, H)
        fun sx(x: Double) = (50 + (x - minX) / (maxX - minX + 1e-9) * (W - 100)).toInt()
        fun sy(y: Double) = (H - 50 - (y - minY) / (maxY - minY + 1e-9) * (H - 100)).toInt()
        for ((p, s) in pts) { g.color = palette[s] ?: Color.GRAY; g.fillOval(sx(p.first) - 2, sy(p.second) - 2, 4, 4) }
        g.color = Color.BLACK
        for ((_, raw) in extras) { val p = proj(std(model, raw)); val x = sx(p.first); val y = sy(p.second)
            g.drawLine(x - 6, y - 6, x + 6, y + 6); g.drawLine(x - 6, y + 6, x + 6, y - 6) }
        // legend (top sound clouds)
        var ly = 16
        for (s in sounds.sortedByDescending { model.labelCounts[it] ?: 0 }.take(20)) {
            g.color = palette[s] ?: Color.GRAY; g.fillRect(W - 180, ly - 8, 10, 10)
            g.color = Color.DARK_GRAY; g.drawString("$s (${model.labelCounts[s] ?: 0})", W - 164, ly); ly += 15
        }
        g.dispose(); ImageIO.write(img, "png", out)
    }

    private fun deflate(cov: Array<DoubleArray>, v: DoubleArray): Array<DoubleArray> {
        val d = cov.size; val lam = quad(cov, v)
        return Array(d) { i -> DoubleArray(d) { j -> cov[i][j] - lam * v[i] * v[j] } }
    }
    private fun quad(cov: Array<DoubleArray>, v: DoubleArray): Double {
        val d = cov.size; var s = 0.0; for (i in 0 until d) { var t = 0.0; for (j in 0 until d) t += cov[i][j] * v[j]; s += v[i] * t }; return s
    }
    private fun powerIter(cov: Array<DoubleArray>, ignored: DoubleArray?): DoubleArray {
        val d = cov.size; var v = DoubleArray(d) { if (it == 0) 1.0 else 0.0 }
        repeat(120) {
            val nv = DoubleArray(d); for (i in 0 until d) { var s = 0.0; for (j in 0 until d) s += cov[i][j] * v[j]; nv[i] = s }
            var n = 0.0; for (x in nv) n += x * x; n = sqrt(n).coerceAtLeast(1e-12); for (i in 0 until d) nv[i] /= n; v = nv
        }
        return v
    }
}
