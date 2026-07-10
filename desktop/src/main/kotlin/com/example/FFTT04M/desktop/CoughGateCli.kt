package com.example.FFTT04M.desktop

import java.io.File

/**
 * Trains + evaluates the stacked cough-isolation gate ([CoughGate]) on the per-segment scores that
 * ALREADY exist for every harvested segment — no GPU re-burn. Joins by segment id:
 *   cough_harvest/harvest_compare.csv   → pWavelet, pHead, srcLabel, wavCall, headCall
 *   cough_harvest/harvest_forest.csv    → pForest
 *   cough_harvest/harvest_hallmark.csv  → hallmarkHit, nHallmarkWindows
 * Weak labels from filename metadata (srcLabel): the hard negatives (breathing/vowel/counting/urban8k
 * DENY cough) are trustworthy → honest specificity; positives are bags (see COUGH_ISOLATION.md).
 *
 * Trains a stacked softmax-LR fuser over [pHead, pWavelet, pForest, hallmarkHit, ln(1+nHallWin)],
 * reports fused 5-fold CV vs each single method (accuracy, cough-F1, and FP-rate on hard negatives —
 * the metric that matters for "don't call speech/breath a cough"), saves data/codebooks/cough_gate.json,
 * and writes cough_harvest/cough_gate.csv (id + per-signal + pFused + verdict).
 *
 * Run: ./gradlew :desktop:coughGate           (defaults to <workspace>/cough_harvest)
 *      ./gradlew :desktop:coughGate -Dgate.harvest=G:\cough_harvest
 */
object CoughGateCli {

    private data class Row(
        val pWav: Double, val pHead: Double, val srcLabel: String,
        val wavCall: Boolean, val headCall: Boolean,
        var pForest: Double = Double.NaN, var hallHit: Boolean = false, var nHall: Int = 0,
        var hasForest: Boolean = false, var hasHall: Boolean = false,
    )

    private fun labelOf(srcLabel: String): String? = when {
        srcLabel.equals("cough", true) -> "cough"
        srcLabel.equals("not_cough", true) || srcLabel.equals("notcough", true) -> "not_cough"
        srcLabel.contains("cough", true) && !srcLabel.contains("not", true) -> "cough"
        srcLabel.isBlank() -> null
        else -> "not_cough"   // breath / speech / vowel / counting / noise all deny cough
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val repo = Workspace.repoRoot ?: File(".")
        val harvest = File(System.getProperty("gate.harvest")?.takeIf { it.isNotBlank() }
            ?: File(repo, "cough_harvest").path)
        val compare = File(harvest, "harvest_compare.csv")
        val forest = File(harvest, "harvest_forest.csv")
        val hallmark = File(harvest, "harvest_hallmark.csv")
        if (!compare.isFile) { println("missing $compare"); return }

        // ---- join by id ----
        val rows = HashMap<String, Row>(200_000)
        compare.useLines { seq ->
            seq.drop(1).forEach { line ->
                val c = line.split(',')
                if (c.size >= 7) rows[c[0]] = Row(
                    pWav = c[3].toDoubleOrNull() ?: Double.NaN,
                    pHead = c[4].toDoubleOrNull() ?: Double.NaN,
                    srcLabel = c[2],
                    wavCall = c[5].equals("true", true),
                    headCall = c[6].equals("true", true))
            }
        }
        if (forest.isFile) forest.useLines { seq ->
            seq.drop(1).forEach { line ->
                val c = line.split(','); val r = rows[c.getOrNull(0)]
                if (r != null && c.size >= 3) { r.pForest = c[2].toDoubleOrNull() ?: Double.NaN; r.hasForest = true }
            }
        }
        if (hallmark.isFile) hallmark.useLines { seq ->
            seq.drop(1).forEach { line ->
                val c = line.split(','); val r = rows[c.getOrNull(0)]
                if (r != null && c.size >= 4) {
                    r.hallHit = c[2].equals("true", true); r.nHall = c[3].toIntOrNull() ?: 0; r.hasHall = true
                }
            }
        }

        // ---- build labelled samples (need all three signals + a usable label) ----
        val feats = CoughGate.FEATURES.take(5)                       // pHead,pWavelet,pForest,hallmarkHit,lnHallWin
        val classes = listOf("not_cough", "cough")
        val ids = ArrayList<String>(); val xs = ArrayList<DoubleArray>(); val ys = ArrayList<String>()
        var skipped = 0
        for ((id, r) in rows) {
            if (!r.hasForest || !r.hasHall) { skipped++; continue }
            val lab = labelOf(r.srcLabel)
            if (lab == null) { skipped++; continue }
            val s = CoughGate.Signals(pHead = r.pHead, pWavelet = r.pWav, pForest = r.pForest,
                hallmarkHit = r.hallHit, nHallWindows = r.nHall)
            ids += id; xs += s.vector(feats); ys += lab
        }
        val nPos = ys.count { it == "cough" }; val nNeg = ys.size - nPos
        println("=== COUGH GATE — stacked fuser over ${feats} ===")
        println("joined ${rows.size} segments · usable ${ys.size} (cough=$nPos, not_cough=$nNeg) · skipped $skipped")
        if (ys.size < 100 || nPos == 0 || nNeg == 0) { println("not enough labelled data to train"); return }

        // ---- 5-fold eval of the fused gate + single-method baselines on the SAME folds ----
        val samples = xs.indices.map { xs[it] to ys[it] }
        val fused = Metrics(); val headB = Metrics(); val wavB = Metrics(); val consB = Metrics(); val hallB = Metrics()
        for (k in 0 until 5) {
            val trIdx = xs.indices.filter { it % 5 != k }
            val teIdx = xs.indices.filter { it % 5 == k }
            if (trIdx.isEmpty() || teIdx.isEmpty()) continue
            val model = WholeClipClassifier.train(trIdx.map { samples[it] }, classes)
            for (i in teIdx) {
                val truth = ys[i] == "cough"
                val (lab, p) = model.predict(xs[i])
                fused.add(truth, lab == "cough")
                val r = rows[ids[i]]!!
                headB.add(truth, r.headCall)
                wavB.add(truth, r.wavCall)
                consB.add(truth, r.headCall && r.wavCall)         // 2-way agreement
                hallB.add(truth, r.hallHit)
            }
        }
        println("method            acc    cough-F1   FP-on-hard-neg   recall")
        for ((name, m) in listOf("FUSED (stacked)" to fused, "head alone" to headB,
                "wavelet alone" to wavB, "hallmark alone" to hallB, "head∧wav consensus" to consB))
            println("  %-18s %5.1f%%   %5.3f       %5.1f%%          %5.1f%%"
                .format(name, m.acc() * 100, m.f1(), m.fpRate() * 100, m.recall() * 100))

        // ---- train final model on ALL data, save, and score every joined segment ----
        val model = WholeClipClassifier.train(samples, classes)
        val outModel = File(Workspace.dir("codebooks"), "cough_gate.json")
        WholeClipClassifier.save(model, outModel)
        val outCsv = File(harvest, "cough_gate.csv")
        outCsv.bufferedWriter().use { w ->
            w.write("id,pHead,pWavelet,pForest,hallmarkHit,nHallWin,srcLabel,pFused,verdict\n")
            for (i in ids.indices) {
                val r = rows[ids[i]]!!
                val (lab, p) = model.predict(xs[i])
                val pCough = if (lab == "cough") p else 1 - p
                w.write("${ids[i]},${fmt(r.pHead)},${fmt(r.pWav)},${fmt(r.pForest)},${r.hallHit},${r.nHall}," +
                    "${r.srcLabel},${"%.3f".format(pCough)},${if (pCough >= 0.5) "cough" else "not_cough"}\n")
            }
        }
        println("saved fuser → $outModel")
        println("wrote per-segment verdicts → $outCsv (${ids.size} rows)")
        println("NOTE: v1 fuses head+wavelet+forest+hallmark. Next: add squiggle (300–2000Hz ridge) + " +
            "speech cues (pitch/flatness/syllabic) per segment; wire CoughGate into a GUI button + raw-stream re-harvest.")
    }

    private fun fmt(d: Double) = if (d.isNaN()) "" else "%.3f".format(d)

    /** Binary confusion accumulator for the positive class "cough". */
    private class Metrics {
        var tp = 0; var fp = 0; var tn = 0; var fn = 0
        fun add(truth: Boolean, pred: Boolean) {
            if (truth && pred) tp++ else if (truth && !pred) fn++ else if (!truth && pred) fp++ else tn++
        }
        fun acc() = (tp + tn).toDouble() / (tp + tn + fp + fn).coerceAtLeast(1)
        fun recall() = tp.toDouble() / (tp + fn).coerceAtLeast(1)
        fun precision() = tp.toDouble() / (tp + fp).coerceAtLeast(1)
        fun f1() = (2 * precision() * recall()) / (precision() + recall()).coerceAtLeast(1e-9)
        fun fpRate() = fp.toDouble() / (fp + tn).coerceAtLeast(1)   // false-cough rate on hard negatives
    }
}
