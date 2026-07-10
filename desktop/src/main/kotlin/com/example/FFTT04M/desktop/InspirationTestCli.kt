package com.example.FFTT04M.desktop

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * Decisive test of the "cough-phase / spectral-trajectory" hypothesis: on COSWARA's matched cough vs
 * breathing clips (which contain the full inspiration→burst sequence), do the temporal [SpectralDynamics]
 * features separate cough from breath BETTER than the static [WholeClipFeatures]? Reports 5-fold
 * cough-vs-breath accuracy / cough-recall / breath-rejection for dynamics-only, static-only, and combined,
 * plus each dynamics feature's standardized cough−breath separation (which phase actually discriminates).
 * DSP only, no GPU. Run: ./gradlew :desktop:inspirationTest -Deval.alldata=D:\AndroidProjects\ALLDATA
 */
object InspirationTestCli {

    private val workers = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private val classes = listOf("breath", "cough")

    @JvmStatic
    fun main(args: Array<String>) {
        val repo = Workspace.repoRoot ?: File(".")
        val alldata = File(System.getProperty("eval.alldata")?.takeIf { it.isNotBlank() } ?: File(repo, "ALLDATA").path)
        val meta = File(alldata, "metadata.csv"); if (!meta.isFile) { println("missing $meta"); return }

        data class Job(val wav: File, val pos: Boolean)
        val jobs = ArrayList<Job>()
        meta.useLines { seq -> seq.drop(1).forEach { line ->
            val c = line.split(','); if (c.size < 4) return@forEach
            if (!c[1].trim().equals("Coswara", true)) return@forEach
            val st = c[3].trim().lowercase()
            val f = File(alldata, c[0].trim()); if (!f.isFile) return@forEach
            when { st.startsWith("cough") -> jobs.add(Job(f, true)); st.startsWith("breathing") -> jobs.add(Job(f, false)) }
        } }
        println("=== INSPIRATION / SPECTRAL-DYNAMICS TEST (coswara cough vs breath) ===")
        println("clips ${jobs.size} (cough=${jobs.count { it.pos }}, breath=${jobs.count { !it.pos }}) · $workers cores")
        if (jobs.size < 200) { println("insufficient"); return }

        val out = ConcurrentLinkedQueue<Triple<DoubleArray, DoubleArray, Boolean>>()  // dyn, static, pos
        val done = AtomicInteger(); val pool = Executors.newFixedThreadPool(workers)
        try {
            jobs.map { j -> pool.submit {
                runCatching {
                    val pcm = AudioDecoder.decode(j.wav)
                    if (pcm != null && pcm.size > 2048) out.add(Triple(SpectralDynamics.extract(pcm, 44100), WholeClipFeatures.extract(pcm, 44100), j.pos))
                }
                val d = done.incrementAndGet(); if (d % 2000 == 0) println("  extracted $d/${jobs.size}")
            } }.forEach { it.get() }
        } finally { pool.shutdown() }
        val data = out.toList()
        val dyn = data.map { it.first to if (it.third) "cough" else "breath" }
        val stat = data.map { it.second to if (it.third) "cough" else "breath" }
        val comb = data.map { (it.first + it.second) to if (it.third) "cough" else "breath" }

        println("\nfeature set        acc    cough-recall  breath-reject")
        eval("SpectralDynamics", dyn); eval("WholeClipFeatures", stat); eval("combined", comb)

        // which dynamics feature discriminates? standardized mean diff (cough − breath)/pooledStd
        println("\n-- SpectralDynamics feature separation (cough vs breath, |d| = Cohen's d) --")
        val pos = data.filter { it.third }.map { it.first }; val neg = data.filter { !it.third }.map { it.first }
        val sep = SpectralDynamics.names.indices.map { k ->
            val mp = pos.map { it[k] }.average(); val mn = neg.map { it[k] }.average()
            val vp = pos.map { (it[k] - mp) * (it[k] - mp) }.average(); val vn = neg.map { (it[k] - mn) * (it[k] - mn) }.average()
            val sd = sqrt((vp + vn) / 2).coerceAtLeast(1e-9)
            Triple(SpectralDynamics.names[k], (mp - mn) / sd, mp to mn)
        }.sortedByDescending { kotlin.math.abs(it.second) }
        for ((name, d, means) in sep)
            println("  %-18s d=%+5.2f   cough=%.3f breath=%.3f".format(name, d, means.first, means.second))
    }

    private fun eval(name: String, samples: List<Pair<DoubleArray, String>>) {
        var tp = 0; var fp = 0; var tn = 0; var fn = 0
        for (k in 0 until 5) {
            val tr = samples.filterIndexed { i, _ -> i % 5 != k }; val te = samples.filterIndexed { i, _ -> i % 5 == k }
            val model = WholeClipClassifier.train(tr, classes)
            for ((x, y) in te) { val pred = model.predict(x).first == "cough"; val truth = y == "cough"
                if (truth && pred) tp++ else if (truth) fn++ else if (pred) fp++ else tn++ }
        }
        val acc = (tp + tn).toDouble() / (tp + tn + fp + fn).coerceAtLeast(1)
        val rec = tp.toDouble() / (tp + fn).coerceAtLeast(1); val rej = tn.toDouble() / (tn + fp).coerceAtLeast(1)
        println("  %-18s %5.1f%%    %6.1f%%      %6.1f%%".format(name, acc * 100, rec * 100, rej * 100))
    }
}
