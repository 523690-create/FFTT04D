package com.example.FFTT04M.desktop

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * KEYSTONE: multi-class post-inspiration classifier over [RespiratoryEvent] look-back features. Tests
 * whether the reliable+phase-correct look-back anchor lets ONE subtype-naive model separate
 * expiration / cough / sneeze / snore / speech — the payoff the forward-anchored detector couldn't reach.
 * Validation data from ALLDATA metadata: cough=coswara cough-*, expiration=coswara breathing-*,
 * speech=coswara vowel/counting, sneeze=ESC-50 sneezing, snore=ESC-50 snoring. 5-fold multi-class LR
 * (class-balanced), confusion matrix + per-class recall + cough-vs-rest, vs a WholeClipFeatures baseline.
 * DSP only, no GPU. Run: -Deval.alldata=D:\AndroidProjects\ALLDATA
 */
object InspirationClassifyCli {

    private val workers = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private val CLASSES = listOf("expiration", "cough", "sneeze", "snore", "speech")
    private const val CAP = 2500

    @JvmStatic
    fun main(args: Array<String>) {
        val repo = Workspace.repoRoot ?: File(".")
        val alldata = File(System.getProperty("eval.alldata")?.takeIf { it.isNotBlank() } ?: File(repo, "ALLDATA").path)
        val meta = File(alldata, "metadata.csv"); if (!meta.isFile) { println("missing $meta"); return }

        val byClass = LinkedHashMap<String, MutableList<File>>()
        CLASSES.forEach { byClass[it] = ArrayList() }
        meta.useLines { seq -> seq.drop(1).forEach { line ->
            val c = line.split(','); if (c.size < 4) return@forEach
            val src = c[1].trim(); val st = c[3].trim().lowercase(); val f = File(alldata, c[0].trim())
            if (!f.isFile) return@forEach
            val cls = when {
                src.equals("Coswara", true) && st.startsWith("cough") -> "cough"
                src.equals("Coswara", true) && st.startsWith("breathing") -> "expiration"
                src.equals("Coswara", true) && (st.startsWith("vowel") || st.startsWith("counting")) -> "speech"
                src.equals("ESC-50", true) && st == "sneezing" -> "sneeze"
                src.equals("ESC-50", true) && st == "snoring" -> "snore"
                else -> null
            }
            if (cls != null) byClass[cls]!!.add(f)
        } }
        // deterministic cap of the big classes
        val jobs = ArrayList<Pair<File, String>>()
        for ((cls, list) in byClass) {
            val use = if (list.size > CAP) (0 until CAP).map { list[it * list.size / CAP] } else list
            use.forEach { jobs.add(it to cls) }
        }
        println("=== KEYSTONE: multi-class post-inspiration classifier (look-back anchor) ===")
        CLASSES.forEach { c -> println("  %-11s %d".format(c, byClass[c]!!.size.let { minOf(it, CAP) })) }
        if (jobs.size < 300) { println("insufficient"); return }

        val out = ConcurrentLinkedQueue<Triple<DoubleArray, DoubleArray, String>>()
        val done = AtomicInteger(); val pool = Executors.newFixedThreadPool(workers)
        try {
            jobs.map { (f, cls) -> pool.submit {
                runCatching { val pcm = AudioDecoder.decode(f); if (pcm != null && pcm.size > 2048) out.add(Triple(RespiratoryEvent.extract(pcm, 44100), WholeClipFeatures.extract(pcm, 44100), cls)) }
                val d = done.incrementAndGet(); if (d % 1500 == 0) println("  $d/${jobs.size}")
            } }.forEach { it.get() }
        } finally { pool.shutdown() }
        val data = out.toList()

        println("\n=== RespiratoryEvent (look-back) 5-fold ===")
        report(data.map { it.first to it.third })
        println("\n=== WholeClipFeatures baseline 5-fold ===")
        report(data.map { it.second to it.third })
        println("\n=== combined 5-fold ===")
        report(data.map { (it.first + it.second) to it.third })
    }

    private fun report(samples: List<Pair<DoubleArray, String>>) {
        val idx = CLASSES.withIndex().associate { (i, c) -> c to i }
        val conf = Array(CLASSES.size) { IntArray(CLASSES.size) }
        for (k in 0 until 5) {
            val tr = samples.filterIndexed { i, _ -> i % 5 != k }; val te = samples.filterIndexed { i, _ -> i % 5 == k }
            val model = WholeClipClassifier.train(tr, CLASSES)
            for ((x, y) in te) conf[idx[y]!!][idx[model.predict(x).first]!!]++
        }
        var correct = 0; var tot = 0
        println("  truth\\pred   " + CLASSES.joinToString("") { it.take(6).padStart(8) } + "   recall")
        for (t in CLASSES.indices) {
            val row = conf[t]; val n = row.sum(); correct += row[t]; tot += n
            val cells = row.joinToString("") { "%8d".format(it) }
            println("  %-11s%s   %5.1f%%".format(CLASSES[t], cells, 100.0 * row[t] / n.coerceAtLeast(1)))
        }
        println("  overall acc %.1f%%".format(100.0 * correct / tot.coerceAtLeast(1)))
        // cough-vs-rest
        val ci = idx["cough"]!!
        var tp = 0; var fp = 0; var fn = 0; var tn = 0
        for (t in CLASSES.indices) for (p in CLASSES.indices) {
            val v = conf[t][p]; val truthC = t == ci; val predC = p == ci
            if (truthC && predC) tp += v else if (truthC) fn += v else if (predC) fp += v else tn += v
        }
        println("  cough-vs-rest: recall %.1f%%  FP %.1f%%  precision %.1f%%".format(
            100.0 * tp / (tp + fn).coerceAtLeast(1), 100.0 * fp / (fp + tn).coerceAtLeast(1), 100.0 * tp / (tp + fp).coerceAtLeast(1)))
    }
}
