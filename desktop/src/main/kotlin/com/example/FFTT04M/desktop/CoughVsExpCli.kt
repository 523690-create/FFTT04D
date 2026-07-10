package com.example.FFTT04M.desktop

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * Best-possible MULTIMODAL cough-vs-EXPIRATION discriminator — the one residual hard boundary (snore/
 * speech/sneeze are already well separated; cough≈breath shares the expulsion physics). Fuses every
 * available modality on coswara's matched cough vs breathing:
 *   - HuBERT content (cached clipemb_ALLDATA.bin — no GPU),
 *   - DSP look-back event shape ([RespiratoryEvent]: inspiration/gap/burst/rapidity),
 *   - static spectral/temporal ([WholeClipFeatures]).
 * Stacks via out-of-fold base scores → meta-LR, with a per-modality ablation, and directly tests the
 * inspiration-RAPIDITY hypothesis (is the inhale steeper/quicker before a cough?). Run:
 * ./gradlew :desktop:coughVsExp -Deval.alldata=D:\AndroidProjects\ALLDATA
 */
object CoughVsExpCli {

    private val workers = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private val classes = listOf("expiration", "cough")

    private fun loadEmb(f: File): HashMap<String, DoubleArray> {
        val m = HashMap<String, DoubleArray>(90_000)
        if (!f.isFile) return m
        DataInputStream(f.inputStream().buffered()).use { dis ->
            while (true) { val id = try { dis.readUTF() } catch (e: EOFException) { break }; val n = dis.readInt(); m[id] = DoubleArray(n) { dis.readFloat().toDouble() } }
        }
        return m
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val repo = Workspace.repoRoot ?: File(".")
        val alldata = File(System.getProperty("eval.alldata")?.takeIf { it.isNotBlank() } ?: File(repo, "ALLDATA").path)
        val emb = loadEmb(File(Workspace.dir("codebooks"), "clipemb_ALLDATA.bin"))
        val meta = File(alldata, "metadata.csv"); if (!meta.isFile) { println("missing $meta"); return }
        if (emb.isEmpty()) println("WARN: no clipemb — HuBERT modality disabled")

        data class Job(val wav: File, val id: String, val pos: Boolean)
        val jobs = ArrayList<Job>()
        meta.useLines { seq -> seq.drop(1).forEach { line ->
            val c = line.split(','); if (c.size < 4 || !c[1].trim().equals("Coswara", true)) return@forEach
            val st = c[3].trim().lowercase(); val id = c[0].trim().removeSuffix(".wav"); val f = File(alldata, c[0].trim())
            if (!f.isFile) return@forEach
            if (st.startsWith("cough")) jobs.add(Job(f, id, true)) else if (st.startsWith("breathing")) jobs.add(Job(f, id, false))
        } }
        println("=== MULTIMODAL cough vs expiration (coswara) ===")
        println("clips ${jobs.size} (cough=${jobs.count { it.pos }}, breath=${jobs.count { !it.pos }}) · emb ${emb.size}")

        val bag = ConcurrentLinkedQueue<Quad>()
        val done = AtomicInteger(); val pool = Executors.newFixedThreadPool(workers)
        try {
            jobs.map { j -> pool.submit {
                runCatching {
                    val pcm = AudioDecoder.decode(j.wav)
                    if (pcm != null && pcm.size > 2048) {
                        val resp = RespiratoryEvent.extract(pcm, 44100); val stat = WholeClipFeatures.extract(pcm, 44100)
                        bag.add(Quad(resp, stat, emb[j.id], j.pos))
                    }
                }
                val d = done.incrementAndGet(); if (d % 2000 == 0) println("  $d/${jobs.size}")
            } }.forEach { it.get() }
        } finally { pool.shutdown() }
        val data = bag.toList()
        val y = data.map { it.pos }
        val hasHub = data.all { it.hub != null } && emb.isNotEmpty()

        // ablation via out-of-fold pCough per modality
        val pStat = oof(data.map { it.stat }, y)
        val pResp = oof(data.map { it.resp }, y)
        val pDsp = oof(data.map { it.resp + it.stat }, y)
        println("\nmodality               acc    cough-recall  breath-reject")
        m("static (WholeClip)", pStat, y); m("DSP look-back event", pResp, y); m("DSP (event+static)", pDsp, y)
        var pHub: DoubleArray? = null
        if (hasHub) { pHub = oof(data.map { it.hub!! }, y); m("HuBERT (content)", pHub, y) }

        // multimodal stack: meta-LR over the base OOF scores
        val stackFeat = data.indices.map {
            if (hasHub) doubleArrayOf(pDsp[it], pHub!![it]) else doubleArrayOf(pStat[it], pResp[it])
        }
        val pFused = oof(stackFeat, y)
        m(if (hasHub) "FUSED DSP+HuBERT" else "FUSED DSP", pFused, y)
        println("\n-- FUSED threshold sweep --   cough-recall  breath-reject  precision")
        for (t in listOf(0.3, 0.4, 0.5, 0.6, 0.7)) { val (r, rej, prec) = stats(pFused, y, t); println("  thr %.2f      %6.1f%%       %6.1f%%      %6.1f%%".format(t, r * 100, rej * 100, prec * 100)) }

        // inspiration-rapidity hypothesis: Cohen's d (cough − breath) for the inhale features
        println("\n-- inspiration-rapidity hypothesis (Cohen's d, cough vs breath) --")
        val pos = data.filter { it.pos }; val neg = data.filter { !it.pos }
        for ((k, nm) in listOf(15 to "inspRiseRate", 16 to "inspPeakFlow", 0 to "inspDurMs", 1 to "inspCentroidSlope")) {
            val mp = pos.map { it.resp[k] }.average(); val mn = neg.map { it.resp[k] }.average()
            val sd = sqrt(((pos.map { (it.resp[k] - mp) * (it.resp[k] - mp) }.average()) + (neg.map { (it.resp[k] - mn) * (it.resp[k] - mn) }.average())) / 2).coerceAtLeast(1e-9)
            println("  %-18s d=%+5.2f  cough=%.3f breath=%.3f".format(nm, (mp - mn) / sd, mp, mn))
        }
    }

    private class Quad(val resp: DoubleArray, val stat: DoubleArray, val hub: DoubleArray?, val pos: Boolean)
    private data class Quad2(val a: Double, val b: Double, val c: Double)  // recall, reject, precision

    /** 5-fold out-of-fold P(cough) for a feature set. */
    private fun oof(x: List<DoubleArray>, y: List<Boolean>): DoubleArray {
        val out = DoubleArray(x.size)
        for (k in 0 until 5) {
            val tr = x.indices.filter { it % 5 != k }; val te = x.indices.filter { it % 5 == k }
            val model = WholeClipClassifier.train(tr.map { x[it] to if (y[it]) "cough" else "expiration" }, classes)
            for (i in te) { val (lab, p) = model.predict(x[i]); out[i] = if (lab == "cough") p else 1 - p }
        }
        return out
    }

    private fun stats(p: DoubleArray, y: List<Boolean>, thr: Double): Quad2 {
        var tp = 0; var fp = 0; var tn = 0; var fn = 0
        for (i in p.indices) { val c = p[i] >= thr; if (y[i] && c) tp++ else if (y[i]) fn++ else if (c) fp++ else tn++ }
        return Quad2(tp.toDouble() / (tp + fn).coerceAtLeast(1), tn.toDouble() / (tn + fp).coerceAtLeast(1), tp.toDouble() / (tp + fp).coerceAtLeast(1))
    }

    private fun m(name: String, p: DoubleArray, y: List<Boolean>) {
        val (r, rej, _) = stats(p, y, 0.5)
        val acc = p.indices.count { (p[it] >= 0.5) == y[it] }.toDouble() / p.size
        println("  %-22s %5.1f%%    %6.1f%%      %6.1f%%".format(name, acc * 100, r * 100, rej * 100))
    }
}
