package com.example.FFTT04M.desktop

import com.example.FFTT04M.desktop.cough.FFTUtils
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Tests the user's key reframing: ANCHOR analysis on the INSPIRATION onset (shared by cough & breath),
 * then classify the POST-inspiration window — controlling for the shared inspiratory phase so cough
 * becomes distinct. Contrast with loudest-frame anchoring (SpectralDynamics: 69.8% dyn / 80.9% combined).
 *
 * Inspiration onset = first sustained rise of broadband (high-flatness, unvoiced) airflow above the
 * clip's noise floor; its local RMS peak ends the inspiration. The post-inspiration window (~800 ms)
 * then carries either a gentle exhale (breath) or a gap→explosive burst (cough). Features describe that
 * window only. Coswara matched cough vs breath, DSP only. Run: -Deval.alldata=D:\AndroidProjects\ALLDATA
 */
object RespiratoryPhaseCli {

    private val workers = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private val classes = listOf("breath", "cough")
    private const val WIN = 1024
    private const val EPS = 1e-9
    val names = listOf("burstRatio", "maxAttack", "gapDepth", "decayFrac", "centroidDrop", "postPeakMs", "inspFound")

    /** per-frame rms, centroid(Hz), flatness. */
    private fun frames(x: FloatArray, sr: Int): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val hop = (sr * 0.010).toInt().coerceAtLeast(1)
        val nf = if (x.size < WIN) 0 else (x.size - WIN) / hop + 1
        val rms = DoubleArray(nf); val cen = DoubleArray(nf); val flat = DoubleArray(nf)
        if (nf < 4) return Triple(rms, cen, flat)
        val hann = FloatArray(WIN) { 0.5f - 0.5f * cos(2f * PI.toFloat() * it / (WIN - 1)) }
        val binHz = sr.toDouble() / WIN; val hiBin = (8000.0 / binHz).toInt().coerceIn(4, WIN / 2 - 1)
        val re = FloatArray(WIN); val im = FloatArray(WIN)
        for (fr in 0 until nf) {
            val o = fr * hop; var e = 0.0
            for (i in 0 until WIN) { re[i] = x[o + i] * hann[i]; im[i] = 0f; e += x[o + i].toDouble() * x[o + i] }
            rms[fr] = sqrt(e / WIN); FFTUtils.compute(re, im)
            var sM = 0.0; var sfM = 0.0; var sLn = 0.0
            for (k in 1 until hiBin) { val mag = sqrt((re[k] * re[k] + im[k] * im[k]).toDouble()); sM += mag; sfM += k * binHz * mag; sLn += ln(mag + EPS) }
            cen[fr] = if (sM > EPS) sfM / sM else 0.0
            flat[fr] = Math.exp(sLn / (hiBin - 1)) / (sM / (hiBin - 1) + EPS)
        }
        return Triple(rms, cen, flat)
    }

    fun extract(x: FloatArray, sr: Int): DoubleArray {
        val (rms, cen, flat) = frames(x, sr)
        val nf = rms.size; if (nf < 12) return DoubleArray(names.size)
        val fps = sr / (sr * 0.010).toInt().coerceAtLeast(1).toDouble()
        val sorted = rms.sortedArray(); val floor = sorted[(0.2 * (nf - 1)).toInt()].coerceAtLeast(EPS)
        val pk = rms.max()

        // inspiration onset: first frame rising above 2×floor with broadband (flat>0.3) airflow
        var onset = -1
        for (i in 1 until nf) if (rms[i] > 2 * floor && flat[i] > 0.3 && rms[i] > rms[i - 1]) { onset = i; break }
        val inspFound = if (onset >= 0) 1.0 else 0.0
        if (onset < 0) onset = 0
        // inspiration end = first local RMS max after onset (airflow peak, before the gap/exhale)
        var inspEnd = onset
        while (inspEnd < nf - 1 && rms[inspEnd + 1] >= rms[inspEnd]) inspEnd++
        val lo = (inspEnd + 1).coerceAtMost(nf - 1)
        val hi = (inspEnd + (0.8 * fps).toInt()).coerceAtMost(nf - 1)
        if (hi - lo < 4) return doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, inspFound)

        // post-inspiration window analysis
        var postPeak = lo; for (i in lo..hi) if (rms[i] > rms[postPeak]) postPeak = i
        val pp = rms[postPeak].coerceAtLeast(EPS)
        val med = (lo..hi).map { rms[it] }.sorted().let { it[it.size / 2] }.coerceAtLeast(EPS)
        val burstRatio = pp / med                                   // cough burst ≫ breath swell
        var maxAttack = 0.0; for (i in (lo + 1)..hi) maxAttack = maxOf(maxAttack, (rms[i] - rms[i - 1]) / pp)
        // glottal gap: dip in the ~150 ms before the post-window peak
        val gLo = (postPeak - (0.15 * fps).toInt()).coerceAtLeast(lo)
        val gapMin = (gLo..postPeak).minOf { rms[it] }
        val gapDepth = 1.0 - gapMin / pp
        // decay: fraction of the post-window above 50% of its peak (short burst → small)
        val decayFrac = (lo..hi).count { rms[it] > 0.5 * pp }.toDouble() / (hi - lo + 1)
        // spectral downward sweep after the burst (cough: high→low)
        val cAfter = ((postPeak + (0.15 * fps).toInt()).coerceAtMost(hi))
        val centroidDrop = cen[postPeak] - cen[cAfter]
        val postPeakMs = (postPeak - lo) * 1000.0 / fps
        return doubleArrayOf(burstRatio, maxAttack, gapDepth, decayFrac, centroidDrop, postPeakMs, inspFound)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val repo = Workspace.repoRoot ?: File(".")
        val alldata = File(System.getProperty("eval.alldata")?.takeIf { it.isNotBlank() } ?: File(repo, "ALLDATA").path)
        val meta = File(alldata, "metadata.csv"); if (!meta.isFile) { println("missing $meta"); return }
        data class Job(val wav: File, val pos: Boolean)
        val jobs = ArrayList<Job>()
        meta.useLines { seq -> seq.drop(1).forEach { line ->
            val c = line.split(','); if (c.size < 4 || !c[1].trim().equals("Coswara", true)) return@forEach
            val st = c[3].trim().lowercase(); val f = File(alldata, c[0].trim()); if (!f.isFile) return@forEach
            if (st.startsWith("cough")) jobs.add(Job(f, true)) else if (st.startsWith("breathing")) jobs.add(Job(f, false))
        } }
        println("=== INSPIRATION-ANCHORED cough vs breath (coswara) ===")
        println("clips ${jobs.size} (cough=${jobs.count { it.pos }}, breath=${jobs.count { !it.pos }})")
        if (jobs.size < 200) { println("insufficient"); return }

        val out = ConcurrentLinkedQueue<Triple<DoubleArray, DoubleArray, Boolean>>()
        val done = AtomicInteger(); val pool = Executors.newFixedThreadPool(workers)
        try {
            jobs.map { j -> pool.submit {
                runCatching { val pcm = AudioDecoder.decode(j.wav); if (pcm != null && pcm.size > 2048) out.add(Triple(extract(pcm, 44100), WholeClipFeatures.extract(pcm, 44100), j.pos)) }
                val d = done.incrementAndGet(); if (d % 2000 == 0) println("  $d/${jobs.size}")
            } }.forEach { it.get() }
        } finally { pool.shutdown() }
        val data = out.toList()

        println("\nfeature set             acc    cough-recall  breath-reject")
        eval("inspiration-anchored", data.map { it.first to if (it.third) "cough" else "breath" })
        eval("WholeClipFeatures", data.map { it.second to if (it.third) "cough" else "breath" })
        eval("combined", data.map { (it.first + it.second) to if (it.third) "cough" else "breath" })

        val pos = data.filter { it.third }.map { it.first }; val neg = data.filter { !it.third }.map { it.first }
        println("\n-- inspiration-anchored feature separation (cough vs breath, Cohen's d) --")
        names.indices.map { k ->
            val mp = pos.map { it[k] }.average(); val mn = neg.map { it[k] }.average()
            val sd = sqrt(((pos.map { (it[k] - mp) * (it[k] - mp) }.average()) + (neg.map { (it[k] - mn) * (it[k] - mn) }.average())) / 2).coerceAtLeast(1e-9)
            Triple(names[k], (mp - mn) / sd, mp to mn)
        }.sortedByDescending { kotlin.math.abs(it.second) }.forEach { (n, d, m) -> println("  %-14s d=%+5.2f  cough=%.3f breath=%.3f".format(n, d, m.first, m.second)) }
    }

    private fun eval(name: String, samples: List<Pair<DoubleArray, String>>) {
        var tp = 0; var fp = 0; var tn = 0; var fn = 0
        for (k in 0 until 5) {
            val tr = samples.filterIndexed { i, _ -> i % 5 != k }; val te = samples.filterIndexed { i, _ -> i % 5 == k }
            val model = WholeClipClassifier.train(tr, classes)
            for ((x, y) in te) { val pred = model.predict(x).first == "cough"; val t = y == "cough"; if (t && pred) tp++ else if (t) fn++ else if (pred) fp++ else tn++ }
        }
        val n = (tp + tn + fp + fn).coerceAtLeast(1)
        println("  %-22s %5.1f%%    %6.1f%%      %6.1f%%".format(name, 100.0 * (tp + tn) / n, 100.0 * tp / (tp + fn).coerceAtLeast(1), 100.0 * tn / (tn + fp).coerceAtLeast(1)))
    }
}
