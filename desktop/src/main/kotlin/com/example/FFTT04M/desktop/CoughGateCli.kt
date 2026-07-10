package com.example.FFTT04M.desktop

import java.io.File

/**
 * Trains + evaluates the stacked cough-isolation gate ([CoughGate]) on the per-segment scores that
 * ALREADY exist for every harvested segment — no GPU re-burn. Joins by segment id:
 *   cough_harvest/harvest_compare.csv   → pWavelet, pHead, srcLabel, wavCall, headCall
 *   cough_harvest/harvest_forest.csv    → pForest
 *   cough_harvest/harvest_hallmark.csv  → hallmarkHit, nHallmarkWindows
 *   cough_harvest/harvest_dsp.csv       → squiggleMaxR2, squiggleCount, pitch, flatness, syllabic  (optional)
 * Weak labels from filename metadata (srcLabel): the hard negatives (breathing/vowel/counting/urban8k
 * DENY cough) are trustworthy → honest specificity; positives are bags (see COUGH_ISOLATION.md).
 *
 * When harvest_dsp.csv is present, compares the 4-content-signal fuser (v1) against the full 6-signal
 * fuser (v2, adds the two ORTHOGONAL DSP signals: squiggle chirp + speech cues) and v2+speech-veto, on
 * the SAME rows, plus a threshold sweep (the specificity/recall knob). Saves data/codebooks/cough_gate.json
 * and writes cough_harvest/cough_gate.csv.
 *
 * Run: ./gradlew :desktop:coughGate -Dgate.harvest=G:\cough_harvest
 */
object CoughGateCli {

    private data class Row(
        val pWav: Double, val pHead: Double, val srcLabel: String,
        val wavCall: Boolean, val headCall: Boolean,
        var pForest: Double = Double.NaN, var hallHit: Boolean = false, var nHall: Int = 0,
        var hasForest: Boolean = false, var hasHall: Boolean = false,
        var sqR2: Double = 0.0, var sqCount: Int = 0, var pitch: Double = 0.0,
        var flat: Double = 1.0, var syll: Double = 0.0, var hasDsp: Boolean = false,
    )

    private val classes = listOf("not_cough", "cough")
    private lateinit var ids: List<String>
    private lateinit var ys: List<String>
    private lateinit var sigs: List<CoughGate.Signals>
    private lateinit var rowsById: Map<String, Row>

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
        File(harvest, "harvest_forest.csv").takeIf { it.isFile }?.useLines { seq ->
            seq.drop(1).forEach { line ->
                val c = line.split(','); val r = rows[c.getOrNull(0)]
                if (r != null && c.size >= 3) { r.pForest = c[2].toDoubleOrNull() ?: Double.NaN; r.hasForest = true }
            }
        }
        File(harvest, "harvest_hallmark.csv").takeIf { it.isFile }?.useLines { seq ->
            seq.drop(1).forEach { line ->
                val c = line.split(','); val r = rows[c.getOrNull(0)]
                if (r != null && c.size >= 4) { r.hallHit = c[2].equals("true", true); r.nHall = c[3].toIntOrNull() ?: 0; r.hasHall = true }
            }
        }
        File(harvest, "harvest_dsp.csv").takeIf { it.isFile }?.useLines { seq ->
            seq.drop(1).forEach { line ->
                val c = line.split(','); val r = rows[c.getOrNull(0)]
                if (r != null && c.size >= 6) {
                    r.sqR2 = c[1].toDoubleOrNull() ?: 0.0; r.sqCount = c[2].toIntOrNull() ?: 0
                    r.pitch = c[3].toDoubleOrNull() ?: 0.0; r.flat = c[4].toDoubleOrNull() ?: 1.0
                    r.syll = c[5].toDoubleOrNull() ?: 0.0; r.hasDsp = true
                }
            }
        }

        // ---- build labelled samples (need content signals + a usable label) ----
        val idL = ArrayList<String>(); val yL = ArrayList<String>(); val sL = ArrayList<CoughGate.Signals>()
        var skipped = 0
        for ((id, r) in rows) {
            if (!r.hasForest || !r.hasHall) { skipped++; continue }
            val lab = labelOf(r.srcLabel) ?: run { skipped++; null } ?: continue
            idL += id; yL += lab
            sL += CoughGate.Signals(pHead = r.pHead, pWavelet = r.pWav, pForest = r.pForest,
                hallmarkHit = r.hallHit, nHallWindows = r.nHall,
                squiggleMaxR2 = r.sqR2, squiggleCount = r.sqCount,
                pitch = if (r.hasDsp) r.pitch else 0.0, flatness = if (r.hasDsp) r.flat else 1.0,
                syllabic = if (r.hasDsp) r.syll else 0.0)
        }
        ids = idL; ys = yL; sigs = sL; rowsById = rows
        val nPos = ys.count { it == "cough" }; val nNeg = ys.size - nPos
        val dspIdx = ids.indices.filter { rows[ids[it]]!!.hasDsp }
        println("=== COUGH GATE ===")
        println("joined ${rows.size} segments · usable ${ys.size} (cough=$nPos, not_cough=$nNeg) · " +
            "with DSP ${dspIdx.size} · skipped $skipped")
        if (ys.size < 100 || nPos == 0 || nNeg == 0) { println("not enough labelled data to train"); return }

        val v1 = CoughGate.FEATURES.take(5)                          // head,wavelet,forest,hallmark
        val v2 = CoughGate.FEATURES                                  // + squiggle + speech cues
        val hasDsp = dspIdx.size >= 100

        println("\n-- all usable rows (${ys.size}) --")
        println("method              acc    cough-F1  FP-hardneg  recall")
        printMetric("FUSED v1 (content)", foldEval(ids.indices.toList(), v1, false))
        printMetric("head alone", singleEval(ids.indices.toList()) { it.headCall })
        printMetric("wavelet alone", singleEval(ids.indices.toList()) { it.wavCall })
        printMetric("hallmark alone", singleEval(ids.indices.toList()) { it.hallHit })
        printMetric("head∧wav consensus", singleEval(ids.indices.toList()) { it.headCall && it.wavCall })

        if (hasDsp) {
            println("\n-- DSP subset (${dspIdx.size}), apples-to-apples v1 vs v2 --")
            println("method              acc    cough-F1  FP-hardneg  recall")
            printMetric("FUSED v1 (content)", foldEval(dspIdx, v1, false))
            printMetric("FUSED v2 (+sq+speech)", foldEval(dspIdx, v2, false))
            printMetric("FUSED v2 + speechVeto", foldEval(dspIdx, v2, true))

            // threshold sweep on pooled out-of-fold predictions for v2 → the specificity/recall knob
            val preds = foldPredict(dspIdx, v2)
            println("\n-- FUSED v2 threshold sweep (specificity knob) --")
            println("thr    FP-hardneg  recall   precision")
            for (t in listOf(0.5, 0.6, 0.7, 0.8, 0.9)) {
                val m = Metrics(); for ((truth, p) in preds) m.add(truth, p >= t)
                println("  %.2f   %5.1f%%     %5.1f%%   %5.1f%%".format(t, m.fpRate() * 100, m.recall() * 100, m.precision() * 100))
            }
        }

        // ---- train final model over the best available feature set, save, and score every joined seg ----
        val feats = if (hasDsp) v2 else v1
        val trainIdx = if (hasDsp) dspIdx else ids.indices.toList()
        val model = WholeClipClassifier.train(trainIdx.map { sigs[it].vector(feats) to ys[it] }, classes)
        val outModel = File(Workspace.dir("codebooks"), "cough_gate.json")
        WholeClipClassifier.save(model, outModel)
        val outCsv = File(harvest, "cough_gate.csv")
        outCsv.bufferedWriter().use { w ->
            w.write("id,pHead,pWavelet,pForest,hallmarkHit,nHallWin,squiggleR2,squiggleN,pitch,flatness,syllabic,srcLabel,pFused,speechVeto,verdict\n")
            for (i in trainIdx) {
                val r = rows[ids[i]]!!; val s = sigs[i]
                val (lab, p) = model.predict(s.vector(feats))
                val pCough = if (lab == "cough") p else 1 - p
                val veto = hasDsp && CoughGate.speechVeto(s)
                val verdict = if (pCough >= 0.5 && !veto) "cough" else "not_cough"
                w.write("${ids[i]},${fmt(r.pHead)},${fmt(r.pWav)},${fmt(r.pForest)},${r.hallHit},${r.nHall}," +
                    "${"%.3f".format(r.sqR2)},${r.sqCount},${"%.3f".format(r.pitch)},${"%.3f".format(r.flat)},${"%.3f".format(r.syll)}," +
                    "${r.srcLabel},${"%.3f".format(pCough)},$veto,$verdict\n")
            }
        }
        println("\nsaved fuser → $outModel  (features: ${feats.size}-dim ${if (hasDsp) "v2" else "v1"})")
        println("wrote per-segment verdicts → $outCsv (${trainIdx.size} rows)")
        if (!hasDsp) println("NOTE: run :desktop:harvestDsp first to add squiggle + speech cues (the v2 signals).")
    }

    // ---- eval helpers over the shared sigs/ys/rowsById ----
    private fun foldEval(idxs: List<Int>, features: List<String>, veto: Boolean): Metrics {
        val m = Metrics()
        for (k in 0 until 5) {
            val tr = idxs.filterIndexed { j, _ -> j % 5 != k }; val te = idxs.filterIndexed { j, _ -> j % 5 == k }
            if (tr.isEmpty() || te.isEmpty()) continue
            val model = WholeClipClassifier.train(tr.map { sigs[it].vector(features) to ys[it] }, classes)
            for (i in te) {
                val (lab, _) = model.predict(sigs[i].vector(features))
                var pred = lab == "cough"; if (veto && CoughGate.speechVeto(sigs[i])) pred = false
                m.add(ys[i] == "cough", pred)
            }
        }
        return m
    }

    private fun foldPredict(idxs: List<Int>, features: List<String>): List<Pair<Boolean, Double>> {
        val out = ArrayList<Pair<Boolean, Double>>(idxs.size)
        for (k in 0 until 5) {
            val tr = idxs.filterIndexed { j, _ -> j % 5 != k }; val te = idxs.filterIndexed { j, _ -> j % 5 == k }
            if (tr.isEmpty() || te.isEmpty()) continue
            val model = WholeClipClassifier.train(tr.map { sigs[it].vector(features) to ys[it] }, classes)
            for (i in te) {
                val (lab, p) = model.predict(sigs[i].vector(features))
                out += (ys[i] == "cough") to (if (lab == "cough") p else 1 - p)
            }
        }
        return out
    }

    private fun singleEval(idxs: List<Int>, pred: (Row) -> Boolean): Metrics {
        val m = Metrics(); for (i in idxs) m.add(ys[i] == "cough", pred(rowsById[ids[i]]!!)); return m
    }

    private fun printMetric(name: String, m: Metrics) =
        println("  %-20s %5.1f%%   %5.3f     %5.1f%%     %5.1f%%"
            .format(name, m.acc() * 100, m.f1(), m.fpRate() * 100, m.recall() * 100))

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
