package com.example.FFTT04M.desktop

import com.example.FFTT04M.desktop.cough.MfccExtractor
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Drives breath false-alarm rate toward the mobile-realistic target. The residual cough-vs-EXPIRATION
 * boundary ([CoughVsExpCli]: 91.6% acc / 94% breath-reject @thr.7) is measured in BALANCED-ACCURACY terms;
 * the real requirement is the ABSOLUTE false-alarm rate under the actual breath base rate (~15
 * breaths/min on a real-time mobile device — thousands/hour, vs rare coughs). 6-9% breath-FP at that base
 * rate is a false cough every 1-2 minutes, unacceptable. This CLI:
 *  1. Reframes the metric: breath-FP @ a FIXED 90% cough-recall operating point, translated directly to
 *     false-alarms/hour.
 *  2. Adds two more modalities on top of [CoughVsExpCli]'s stack (HuBERT clip embed + [RespiratoryEvent]
 *     look-back + [WholeClipFeatures]) and measures each one's MARGINAL gain on that metric: segment-level
 *     HuBERT (cached `segemb_ALLDATA.bin`, mean-pooled per clip) and MFCC dynamics (mean/std/frame-delta).
 *  3. Hard-negative mining: lists the worst (most cough-like) breathing clips by fused score, with their
 *     key features, so the residual failure mode is inspectable.
 *  4. Upweighted retrain: 2 rounds of reweighting the top-25% hardest breathing samples 3x in the fused
 *     meta-stack, re-measuring the metric each round.
 *  5. End-to-end cascade: one-class cough-library prefilter (rejects far negatives, K=64 k-means over
 *     held-out-fold cough embeddings, per [CoughOneClassCli]'s technique) -> the discriminative fused gate
 *     at its 90%-recall threshold — reports the COMBINED recall/false-alarms-per-hour vs the gate alone.
 *
 * All cached/no-GPU (clipemb/segemb .bin caches only). Run:
 *   ./gradlew :desktop:breathSpec -Deval.alldata=D:\AndroidProjects\ALLDATA [-Dbreath.rate=15]
 */
object BreathSpecCli {

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

    private class Sample(val id: String, val resp: DoubleArray, val stat: DoubleArray, val mfcc: DoubleArray, val hub: DoubleArray?, val seg: DoubleArray?, val pos: Boolean)

    /** static timbre (13) + variability (13) + frame-to-frame delta "dynamics" (13) = 39-dim. */
    private fun mfccDynamics(x: FloatArray, sr: Int): DoubleArray {
        val frames = MfccExtractor().frames(x, 0, x.size, sr)
        if (frames.size < 3) return DoubleArray(39)
        val k = frames[0].size
        val mean = DoubleArray(k); for (fr in frames) for (i in 0 until k) mean[i] += fr[i]; for (i in 0 until k) mean[i] /= frames.size
        val std = DoubleArray(k); for (fr in frames) for (i in 0 until k) { val d = fr[i] - mean[i]; std[i] += d * d }; for (i in 0 until k) std[i] = sqrt(std[i] / frames.size)
        val delta = DoubleArray(k)
        for (j in 1 until frames.size) for (i in 0 until k) delta[i] += abs(frames[j][i] - frames[j - 1][i])
        val nDelta = (frames.size - 1).coerceAtLeast(1)
        for (i in 0 until k) delta[i] /= nDelta
        return mean + std + delta
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val repo = Workspace.repoRoot ?: File(".")
        val alldata = File(System.getProperty("eval.alldata")?.takeIf { it.isNotBlank() } ?: File(repo, "ALLDATA").path)
        val breathRate = System.getProperty("breath.rate")?.toDoubleOrNull() ?: 15.0   // breaths/min, real-time mobile base rate
        val clipEmb = loadEmb(File(Workspace.dir("codebooks"), "clipemb_ALLDATA.bin"))
        val segEmb = loadEmb(File(Workspace.dir("codebooks"), "segemb_ALLDATA.bin"))
        val meta = File(alldata, "metadata.csv"); if (!meta.isFile) { println("missing $meta"); return }
        if (clipEmb.isEmpty()) println("WARN: no clipemb — HuBERT modality disabled")

        // group segment embeddings by parent clip id ("<id>#<w>", per UnsupervisedCluster's writeEntry)
        val segByParent = HashMap<String, ArrayList<DoubleArray>>(20_000)
        for ((k, v) in segEmb) { val h = k.lastIndexOf('#'); if (h <= 0) continue; val pid = k.substring(0, h); segByParent.getOrPut(pid) { ArrayList() }.add(v) }

        data class Job(val wav: File, val id: String, val pos: Boolean)
        val jobs = ArrayList<Job>()
        meta.useLines { seq -> seq.drop(1).forEach { line ->
            val c = line.split(','); if (c.size < 4 || !c[1].trim().equals("Coswara", true)) return@forEach
            val st = c[3].trim().lowercase(); val id = c[0].trim().removeSuffix(".wav"); val f = File(alldata, c[0].trim())
            if (!f.isFile) return@forEach
            if (st.startsWith("cough")) jobs.add(Job(f, id, true)) else if (st.startsWith("breathing")) jobs.add(Job(f, id, false))
        } }
        println("=== BREATH SPECIFICITY REFINEMENT (coswara cough vs breathing) ===")
        println("clips ${jobs.size} (cough=${jobs.count { it.pos }}, breath=${jobs.count { !it.pos }}) · clipEmb ${clipEmb.size} · segEmb parent-clips ${segByParent.size}")
        println("assumed breath base rate: $breathRate breaths/min (${(breathRate * 60).toInt()}/hour)")

        val bag = ConcurrentLinkedQueue<Sample>()
        val done = AtomicInteger(); val pool = Executors.newFixedThreadPool(workers)
        try {
            jobs.map { j -> pool.submit {
                runCatching {
                    val pcm = AudioDecoder.decode(j.wav)
                    if (pcm != null && pcm.size > 2048) {
                        val resp = RespiratoryEvent.extract(pcm, 44100); val stat = WholeClipFeatures.extract(pcm, 44100)
                        val mfcc = mfccDynamics(pcm, 44100)
                        val segs = segByParent[j.id]
                        val segMean = segs?.let { list -> val d = list[0].size; val v = DoubleArray(d); for (s in list) for (i in 0 until d) v[i] += s[i]; for (i in 0 until d) v[i] /= list.size; v }
                        bag.add(Sample(j.id, resp, stat, mfcc, clipEmb[j.id], segMean, j.pos))
                    }
                }.onFailure { System.err.println("skip ${j.id}: ${it.message}") }
                val d = done.incrementAndGet(); if (d % 1000 == 0) println("  $d/${jobs.size}")
            } }.forEach { it.get() }
        } finally { pool.shutdown() }

        val data = bag.toList()
        val y = data.map { it.pos }
        val hubIdx = data.indices.filter { data[it].hub != null }
        val segIdx = data.indices.filter { data[it].seg != null }
        val hasHub = hubIdx.size > data.size / 2
        val hasSeg = segIdx.size >= 200
        println("\nsamples ${data.size} · feature coverage: HuBERT ${hubIdx.size}/${data.size} · segHuBERT ${segIdx.size}/${data.size}")

        // ---- per-modality OOF ----
        val ids = data.map { it.id }
        val pStat = oof(data.map { it.stat }, y, ids)
        val pResp = oof(data.map { it.resp }, y, ids)
        val pDsp = oof(data.map { it.resp + it.stat }, y, ids)
        val pMfcc = oof(data.map { it.mfcc }, y, ids)
        var pHub: DoubleArray? = null
        var pSeg: DoubleArray? = null
        if (hasHub) pHub = oofMasked(data.map { it.hub }, y, ids)
        if (hasSeg) pSeg = oofMasked(data.map { it.seg }, y, ids)

        println("\nmodality               acc    cough-recall  breath-reject")
        m("static (WholeClip)", pStat, y); m("DSP look-back event", pResp, y); m("DSP (event+static)", pDsp, y)
        m("MFCC dynamics", pMfcc, y, data.indices.toList())
        pHub?.let { m("HuBERT (clip)", it, y, hubIdx) }
        pSeg?.let { m("HuBERT (segment-mean)", it, y, segIdx) }

        // ---- fused stacks: baseline (dsp+hub, matches CoughVsExpCli) vs incremental additions ----
        fun stackOf(useMfcc: Boolean, useSeg: Boolean) = data.indices.map { i ->
            val base = if (hasHub) doubleArrayOf(pDsp[i], pHub!![i]) else doubleArrayOf(pStat[i], pResp[i])
            val withMfcc = if (useMfcc) base + pMfcc[i] else base
            if (useSeg && hasSeg) withMfcc + pSeg!![i] else withMfcc
        }
        val pFusedBase = oof(stackOf(false, false), y, ids)
        m(if (hasHub) "FUSED DSP+HuBERT" else "FUSED DSP", pFusedBase, y)
        val pFusedMfcc = oof(stackOf(true, false), y, ids)
        m("FUSED +MFCC-dyn", pFusedMfcc, y)
        var pFusedAll = pFusedMfcc
        if (hasSeg) {
            pFusedAll = oof(stackOf(true, true), y, ids)
            m("FUSED +MFCC+segHuBERT", pFusedAll, y)
        }

        // ---- THE metric that matters: breath-FP @ fixed 90% cough recall -> false-alarms/hour ----
        println("\n=== OPERATING POINT: breath-FP @ 90%% cough recall -> false-alarms/hour (base rate $breathRate/min) ===")
        report("static", pStat, y, breathRate)
        report("DSP (event+static)", pDsp, y, breathRate)
        report("MFCC dynamics", pMfcc, y, breathRate)
        pHub?.let { report("HuBERT (clip)", it, y, breathRate) }
        report("FUSED DSP+HuBERT (baseline)", pFusedBase, y, breathRate)
        report("FUSED +MFCC-dyn", pFusedMfcc, y, breathRate)
        if (hasSeg) report("FUSED +MFCC+segHuBERT", pFusedAll, y, breathRate)

        // ---- hard-negative mining on the best fused score ----
        println("\n=== HARD-NEGATIVE MINING: worst (most cough-like) breathing clips, by best FUSED score ===")
        data.indices.filter { !y[it] }.sortedByDescending { pFusedAll[it] }.take(15).forEach { i ->
            val s = data[i]
            println("  %-28s p=%.3f gapDepth=%.2f inspRise=%.2f attack=%.0fms width=%.0fms hf=%.2f crest=%.2f onsetSharp=%.2f"
                .format(s.id, pFusedAll[i], s.resp[3], s.resp[15], s.resp[5], s.resp[7], s.resp[9], s.stat[0], s.stat[2]))
        }

        // ---- upweighted retrain rounds (mine hard negatives -> upweight -> re-measure), x2 ----
        println("\n=== HARD-NEGATIVE UPWEIGHTED RETRAIN (fused meta-stack only; 2 rounds, top-25%% breath x3) ===")
        val stackFinal = stackOf(true, hasSeg)
        var curFused = pFusedAll
        val weights = DoubleArray(data.size) { 1.0 }
        for (round in 1..2) {
            val breathByScore = data.indices.filter { !y[it] }.sortedByDescending { curFused[it] }
            val cut = breathByScore.take((breathByScore.size * 0.25).toInt().coerceAtLeast(1)).toSet()
            for (i in cut) weights[i] *= 3.0
            curFused = oofWeighted(stackFinal, y, weights, ids)
            m("round $round upweighted", curFused, y)
            report("round $round upweighted", curFused, y, breathRate)
        }

        // ---- deeper lever: upweight hard negatives INSIDE the base HuBERT modality itself. The
        // 3-4 feature meta-stack has too little capacity to move much (round 2 above gave zero further
        // gain); the actual base classifier (768-dim HuBERT LR) has far more capacity to reshape its
        // decision boundary around specific hard breaths. ----
        var pFusedHubUp = curFused
        if (hasHub) {
            println("\n=== BASE-MODALITY UPWEIGHT: retrain HuBERT itself on hard-negative-weighted breath ===")
            val nBreath = data.indices.count { !y[it] }
            val hardBreath = data.indices.filter { !y[it] }.sortedByDescending { curFused[it] }
                .take((nBreath * 0.25).toInt().coerceAtLeast(1)).toSet()
            val hubWeights = DoubleArray(data.size) { if (it in hardBreath) 3.0 else 1.0 }
            val pHubUp = oofMaskedWeighted(data.map { it.hub }, y, hubWeights, ids)
            m("HuBERT (hard-neg upweighted)", pHubUp, y, hubIdx)
            report("HuBERT (hard-neg upweighted)", pHubUp, y, breathRate, hubIdx)
            val stackHubUp = data.indices.map { i -> doubleArrayOf(pDsp[i], pHubUp[i], pMfcc[i]) }
            pFusedHubUp = oof(stackHubUp, y, ids)
            m("FUSED (HuBERT upweighted)", pFusedHubUp, y)
            report("FUSED (HuBERT upweighted)", pFusedHubUp, y, breathRate)
        }

        // ---- nonlinear capacity test: is the residual a genuine information ceiling, or just a LINEAR
        // capacity ceiling? WholeClipClassifier is strictly linear; swap in a small ReLU MLP (same 768-dim
        // HuBERT input) and see if it separates the persistent hard negatives any better. ----
        var pFusedBest = pFusedHubUp
        if (hasHub) {
            println("\n=== NONLINEAR CAPACITY TEST: MLP vs linear on the SAME HuBERT embeddings ===")
            val nBreath = data.indices.count { !y[it] }
            val hardBreath = data.indices.filter { !y[it] }.sortedByDescending { pFusedHubUp[it] }
                .take((nBreath * 0.25).toInt().coerceAtLeast(1)).toSet()
            val hubWeights = DoubleArray(data.size) { if (it in hardBreath) 3.0 else 1.0 }
            val pHubMlp = oofMlpMasked(data.map { it.hub }, y, ids)
            m("HuBERT (MLP)", pHubMlp, y, hubIdx)
            report("HuBERT (MLP)", pHubMlp, y, breathRate, hubIdx)
            val pHubMlpUp = oofMlpMasked(data.map { it.hub }, y, ids, hubWeights)
            m("HuBERT (MLP, hard-neg upweighted)", pHubMlpUp, y, hubIdx)
            report("HuBERT (MLP, hard-neg upweighted)", pHubMlpUp, y, breathRate, hubIdx)
            val stackMlp = data.indices.map { i -> doubleArrayOf(pDsp[i], pHubMlpUp[i], pMfcc[i]) }
            val pFusedMlp = oof(stackMlp, y, ids)
            m("FUSED (HuBERT-MLP upweighted)", pFusedMlp, y)
            report("FUSED (HuBERT-MLP upweighted)", pFusedMlp, y, breathRate)
            // keep whichever base-modality variant (linear-upweighted vs MLP-upweighted) actually won,
            // by breath-FP at the shared 90%-recall operating point -- feed the WINNER into the cascade.
            fun fpAt90(p: DoubleArray): Double {
                val cs = data.indices.filter { y[it] }.map { p[it] }.sortedDescending()
                val thr = cs[(0.90 * (cs.size - 1)).toInt()]
                val fp = data.indices.count { !y[it] && p[it] >= thr }; val tn = data.indices.count { !y[it] && p[it] < thr }
                return fp.toDouble() / (fp + tn).coerceAtLeast(1)
            }
            pFusedBest = if (fpAt90(pFusedMlp) < fpAt90(pFusedHubUp)) pFusedMlp else pFusedHubUp
            println("  winner feeding the cascade: ${if (pFusedBest === pFusedMlp) "MLP" else "linear"} variant")
        }

        // ---- end-to-end cascade: one-class cough library -> discriminative fused gate ----
        if (hasHub) {
            println("\n=== END-TO-END CASCADE: one-class cough library -> discriminative fused gate ===")
            cascade(data, y, pFusedBest, breathRate)
        }
    }

    /** Deterministic id-hash fold (same clip -> same fold across every oof() call and every run,
     *  regardless of the concurrent-decode thread pool's completion order). */
    private fun fold(id: String) = ((id.hashCode() % 5) + 5) % 5

    /** 5-fold out-of-fold P(cough) for a feature set (full coverage). */
    private fun oof(x: List<DoubleArray>, y: List<Boolean>, ids: List<String>): DoubleArray {
        val out = DoubleArray(x.size)
        for (k in 0 until 5) {
            val tr = x.indices.filter { fold(ids[it]) != k }; val te = x.indices.filter { fold(ids[it]) == k }
            val model = WholeClipClassifier.train(tr.map { x[it] to if (y[it]) "cough" else "expiration" }, classes)
            for (i in te) { val (lab, p) = model.predict(x[i]); out[i] = if (lab == "cough") p else 1 - p }
        }
        return out
    }

    /** 5-fold OOF over a possibly-null feature set — trains/tests only on covered indices, neutral (0.5) elsewhere. */
    private fun oofMasked(x: List<DoubleArray?>, y: List<Boolean>, ids: List<String>): DoubleArray {
        val out = DoubleArray(x.size) { 0.5 }
        val idx = x.indices.filter { x[it] != null }
        if (idx.size < 50) return out
        for (k in 0 until 5) {
            val trI = idx.filter { fold(ids[it]) != k }; val teI = idx.filter { fold(ids[it]) == k }
            if (trI.isEmpty() || teI.isEmpty()) continue
            val model = WholeClipClassifier.train(trI.map { x[it]!! to if (y[it]) "cough" else "expiration" }, classes)
            for (i in teI) { val (lab, p) = model.predict(x[i]!!); out[i] = if (lab == "cough") p else 1 - p }
        }
        return out
    }

    /** 5-fold OOF with per-sample weights fed into the meta-LR (hard-negative upweighting). */
    private fun oofWeighted(x: List<DoubleArray>, y: List<Boolean>, w: DoubleArray, ids: List<String>): DoubleArray {
        val out = DoubleArray(x.size)
        for (k in 0 until 5) {
            val tr = x.indices.filter { fold(ids[it]) != k }; val te = x.indices.filter { fold(ids[it]) == k }
            val trW = DoubleArray(tr.size) { w[tr[it]] }
            val model = WholeClipClassifier.train(tr.map { x[it] to if (y[it]) "cough" else "expiration" }, classes, sampleWeight = trW)
            for (i in te) { val (lab, p) = model.predict(x[i]); out[i] = if (lab == "cough") p else 1 - p }
        }
        return out
    }

    /** 5-fold OOF over a possibly-null feature set, WITH per-sample weights on the covered subset
     *  (hard-negative upweighting applied directly to a base modality, not just the meta-stack). */
    private fun oofMaskedWeighted(x: List<DoubleArray?>, y: List<Boolean>, w: DoubleArray, ids: List<String>): DoubleArray {
        val out = DoubleArray(x.size) { 0.5 }
        val idx = x.indices.filter { x[it] != null }
        if (idx.size < 50) return out
        for (k in 0 until 5) {
            val trI = idx.filter { fold(ids[it]) != k }; val teI = idx.filter { fold(ids[it]) == k }
            if (trI.isEmpty() || teI.isEmpty()) continue
            val trW = DoubleArray(trI.size) { w[trI[it]] }
            val model = WholeClipClassifier.train(trI.map { x[it]!! to if (y[it]) "cough" else "expiration" }, classes, sampleWeight = trW)
            for (i in teI) { val (lab, p) = model.predict(x[i]!!); out[i] = if (lab == "cough") p else 1 - p }
        }
        return out
    }

    /** 5-fold OOF over a possibly-null feature set using [Mlp] (nonlinear capacity test) instead of the
     *  linear [WholeClipClassifier], optional per-sample weights. */
    private fun oofMlpMasked(x: List<DoubleArray?>, y: List<Boolean>, ids: List<String>, w: DoubleArray? = null): DoubleArray {
        val out = DoubleArray(x.size) { 0.5 }
        val idx = x.indices.filter { x[it] != null }
        if (idx.size < 50) return out
        for (k in 0 until 5) {
            val trI = idx.filter { fold(ids[it]) != k }; val teI = idx.filter { fold(ids[it]) == k }
            if (trI.isEmpty() || teI.isEmpty()) continue
            val trW = w?.let { ww -> DoubleArray(trI.size) { ww[trI[it]] } }
            val model = Mlp.train(trI.map { x[it]!! to if (y[it]) "cough" else "expiration" }, classes, sampleWeight = trW)
            for (i in teI) { val (lab, p) = model.predict(x[i]!!); out[i] = if (lab == "cough") p else 1 - p }
        }
        return out
    }

    private fun m(name: String, p: DoubleArray, y: List<Boolean>, idx: List<Int> = y.indices.toList()) {
        var tp = 0; var fn = 0; var tn = 0; var fp = 0
        for (i in idx) { val c = p[i] >= 0.5; if (y[i] && c) tp++ else if (y[i]) fn++ else if (c) fp++ else tn++ }
        val acc = (tp + tn).toDouble() / idx.size
        val recall = tp.toDouble() / (tp + fn).coerceAtLeast(1); val reject = tn.toDouble() / (tn + fp).coerceAtLeast(1)
        println("  %-22s %5.1f%%    %6.1f%%      %6.1f%%".format(name, acc * 100, recall * 100, reject * 100))
    }

    /** The metric that matters: threshold set to hit ~90% recall on TRUE coughs, then measure breath-FP
     *  at that threshold and translate directly into false-alarms/hour under the real breath base rate. */
    private fun report(name: String, p: DoubleArray, y: List<Boolean>, breathRate: Double, idx: List<Int> = y.indices.toList()) {
        val coughScores = idx.filter { y[it] }.map { p[it] }.sortedDescending()
        if (coughScores.size < 10) { println("  %-28s insufficient cough samples".format(name)); return }
        val thr = coughScores[(0.90 * (coughScores.size - 1)).toInt()]
        var tp = 0; var fn = 0; var fp = 0; var tn = 0
        for (i in idx) { val c = p[i] >= thr; if (y[i] && c) tp++ else if (y[i]) fn++ else if (c) fp++ else tn++ }
        val recall = tp.toDouble() / (tp + fn).coerceAtLeast(1)
        val fpr = fp.toDouble() / (fp + tn).coerceAtLeast(1)
        val alarmsPerHour = breathRate * 60.0 * fpr
        println("  %-28s thr=%.3f recall=%5.1f%%  breath-FP=%5.1f%%  alarms/hr=%7.1f".format(name, thr, recall * 100, fpr * 100, alarmsPerHour))
    }

    /** End-to-end: one-class cough library (far-negative rejector, K=64 k-means on held-out-fold cough
     *  clip embeddings) as stage 1, then the fused discriminative gate at its 90%-recall threshold as
     *  stage 2. Sweeps the stage-1 recall target (90/95/97/99%) — a gentler stage-1 threshold sheds
     *  fewer true coughs at the cost of rejecting less far-negative breath — to find the best tradeoff
     *  instead of assuming 90% was the right cut. Reports combined recall/breath-FP/alarms-per-hour at
     *  each stage-1 setting vs stage-2-alone. */
    private fun cascade(data: List<Sample>, y: List<Boolean>, pFused: DoubleArray, breathRate: Double) {
        fun norm(v: DoubleArray): DoubleArray { var s = 0.0; for (x in v) s += x * x; val n = sqrt(s).coerceAtLeast(1e-9); return DoubleArray(v.size) { v[it] / n } }
        fun dist2(a: DoubleArray, b: DoubleArray): Double { var s = 0.0; for (i in a.indices) { val d = a[i] - b[i]; s += d * d }; return s }

        val coughIdx = data.indices.filter { y[it] && data[it].hub != null }
        val trainIdx = coughIdx.filter { fold(data[it].id) != 0 }
        val testIdx = coughIdx.filter { fold(data[it].id) == 0 }
        if (trainIdx.size < 200 || testIdx.size < 50) { println("  insufficient cough embeddings for one-class stage"); return }

        val K = 64; val cap = 8000
        val trainEmb = trainIdx.map { norm(data[it].hub!!) }
        val train = if (trainEmb.size > cap) (0 until cap).map { trainEmb[it * trainEmb.size / cap] } else trainEmb
        var cent = (0 until K).map { train[it * train.size / K].copyOf() }
        repeat(12) {
            val d = train[0].size
            val sum = Array(K) { DoubleArray(d) }; val cnt = IntArray(K)
            for (x in train) {
                var bi = 0; var bd = Double.MAX_VALUE
                for (k in 0 until K) { val dd = dist2(x, cent[k]); if (dd < bd) { bd = dd; bi = k } }
                cnt[bi]++; val s = sum[bi]; for (j in x.indices) s[j] += x[j]
            }
            cent = (0 until K).map { k -> if (cnt[k] > 0) DoubleArray(d) { sum[k][it] / cnt[k] } else cent[k] }
        }
        fun nearest(x: DoubleArray): Double { var mn = Double.MAX_VALUE; for (c in cent) { val d = dist2(x, c); if (d < mn) mn = d }; return mn }

        // precompute distances ONCE (the expensive part), then sweep stage-1 thresholds cheaply
        val testDist = testIdx.map { nearest(norm(data[it].hub!!)) }
        val breathIdx = data.indices.filter { !y[it] && data[it].hub != null }
        val breathDist = breathIdx.map { nearest(norm(data[it].hub!!)) }

        val coughAllScores = data.indices.filter { y[it] }.map { pFused[it] }.sortedDescending()
        val thr2 = coughAllScores[(0.90 * (coughAllScores.size - 1)).toInt()]
        println("  stage2 threshold (fused gate, ~90%% recall op point): %.3f".format(thr2))

        for (targetRecall in listOf(0.90, 0.95, 0.97, 0.99)) {
            val sortedD = testDist.sorted()
            val thr1 = sortedD[(targetRecall * (sortedD.size - 1)).toInt()]
            val recallStage1 = testDist.count { it <= thr1 }.toDouble() / testDist.size
            val stage1FpRate = breathDist.count { it <= thr1 }.toDouble() / breathDist.size

            val e2eRecall = testIdx.indices.count { i -> testDist[i] <= thr1 && pFused[testIdx[i]] >= thr2 }.toDouble() / testIdx.size
            val e2eFpCount = breathIdx.indices.count { i -> breathDist[i] <= thr1 && pFused[breathIdx[i]] >= thr2 }
            val e2eFpRate = e2eFpCount.toDouble() / breathIdx.size

            println("  stage1 target %.0f%%: actual cough recall %5.1f%% | breath survives %5.1f%%  ->  END-TO-END recall %5.1f%%  breath-FP %5.1f%%  alarms/hr %6.1f"
                .format(targetRecall * 100, recallStage1 * 100, stage1FpRate * 100, e2eRecall * 100, e2eFpRate * 100, breathRate * 60 * e2eFpRate))
        }
        println("  (compare vs stage2-ALONE breath-FP/alarms-hr in the OPERATING POINT table above)")
    }
}
