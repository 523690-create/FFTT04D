package com.example.FFTT04M.desktop

import java.io.File
import kotlin.math.ln

/**
 * CLIP-LEVEL meta-fuser — the recommended best cough/not-cough gate (COUGH_ISOLATION.md). Fuses BOTH
 * method families that the competitive eval showed are complementary (union recall 89% ≫ either ~74–77%):
 *   - whole-clip (segmenter-independent): forest P(cough), squiggle maxR²/count, pitch/flatness/syllabic
 *     from cough_wholeclip.csv — catches short/quiet coughs the segmenter misses (dataset_1sec 96%);
 *   - seg-OR aggregates (per parent clip, from the harvest CSVs): max pHead / pWavelet / pForest / pFused,
 *     any-hallmark, #candidates, max squiggle-R² — the content signals with low breathing FP.
 *
 * Labels are CANONICAL and CLEAN at clip granularity (unlike per-segment): a positive BAG clip genuinely
 * contains a cough → clip=cough; a hard-negative clip has none → clip=not_cough (CoughTruth, conditions
 * 3&4 via ALLDATA is_cough). Trains a softmax-LR fuser (WholeClipClassifier), 5-fold CV, reports the
 * fused threshold sweep vs the best single methods + union/intersect on the SAME clips, plus per-source
 * recall/FP (condition 5). Saves data/codebooks/cough_clipgate.json + cough_harvest/cough_clipgate.csv.
 *
 * Run: ./gradlew :desktop:clipGate -Deval.alldata=D:\AndroidProjects\ALLDATA -Deval.harvest=D:\AndroidProjects\cough_harvest
 */
object ClipGateCli {

    private val SUFFIX = Regex("__cough\\d+_\\d+-\\d+ms$")
    private fun parentOf(segId: String) = segId.replace(SUFFIX, "")
    private val classes = listOf("not_cough", "cough")
    val FEATURES = listOf(
        "wc_forest", "wc_sqR2", "wc_lnSqN", "wc_pitch", "wc_flat", "wc_syll",
        "seg_maxHead", "seg_maxWav", "seg_maxFused", "seg_maxForest", "seg_hall", "seg_lnCand", "seg_maxSqR2",
        "spec_cough")   // coswara-matched cough-vs-breath/speech HuBERT specialist (OOF), from cough_specialist.csv

    private class Agg {
        var maxHead = 0.0; var maxWav = 0.0; var maxFused = 0.0; var maxForest = 0.0; var maxSqR2 = 0.0
        var hall = false; var nCand = 0
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val repo = Workspace.repoRoot ?: File(".")
        val alldata = File(System.getProperty("eval.alldata")?.takeIf { it.isNotBlank() } ?: File(repo, "ALLDATA").path)
        val harvest = File(System.getProperty("eval.harvest")?.takeIf { it.isNotBlank() } ?: File(repo, "cough_harvest").path)
        val meta = File(alldata, "metadata.csv")
        val wcFile = File(harvest, "cough_wholeclip.csv")
        if (!meta.isFile || !wcFile.isFile) { println("need $meta and $wcFile (run allDataScore first)"); return }

        // truth per clip
        data class Truth(val source: String, val soundType: String, val pos: Boolean)
        val truth = HashMap<String, Truth>(90_000)
        meta.useLines { seq ->
            seq.drop(1).forEach { line ->
                val c = line.split(','); if (c.size < 5) return@forEach
                val t = CoughTruth.fromIsCough(CoughTruth.parseIsCough(c[4]))
                if (t != CoughTruth.Truth.SKIP)
                    truth[c[0].trim().removeSuffix(".wav")] = Truth(c[1].trim(), c[3].trim(), t == CoughTruth.Truth.POS)
            }
        }

        // whole-clip features
        val wc = HashMap<String, DoubleArray>(90_000)
        wcFile.useLines { seq ->
            seq.drop(1).forEach { line ->
                val c = line.split(','); if (c.size < 7) return@forEach
                wc[c[0]] = doubleArrayOf(
                    c[1].toDoubleOrNull() ?: 0.0, c[2].toDoubleOrNull() ?: 0.0, ln(1.0 + (c[3].toIntOrNull() ?: 0)),
                    c[4].toDoubleOrNull() ?: 0.0, c[5].toDoubleOrNull() ?: 1.0, c[6].toDoubleOrNull() ?: 0.0)
            }
        }

        // seg-OR aggregates per parent clip
        val agg = HashMap<String, Agg>(90_000)
        fun a(id: String) = agg.getOrPut(parentOf(id)) { Agg() }
        File(harvest, "harvest_compare.csv").takeIf { it.isFile }?.useLines { s -> s.drop(1).forEach { line ->
            val c = line.split(','); if (c.size < 7) return@forEach; val g = a(c[0])
            g.maxWav = maxOf(g.maxWav, c[3].toDoubleOrNull() ?: 0.0); g.maxHead = maxOf(g.maxHead, c[4].toDoubleOrNull() ?: 0.0); g.nCand++
        } }
        File(harvest, "harvest_forest.csv").takeIf { it.isFile }?.useLines { s -> s.drop(1).forEach { line ->
            val c = line.split(','); if (c.size < 3) return@forEach; val g = a(c[0]); g.maxForest = maxOf(g.maxForest, c[2].toDoubleOrNull() ?: 0.0)
        } }
        File(harvest, "harvest_hallmark.csv").takeIf { it.isFile }?.useLines { s -> s.drop(1).forEach { line ->
            val c = line.split(','); if (c.size < 3) return@forEach; if (c[2].equals("true", true)) a(c[0]).hall = true
        } }
        File(harvest, "harvest_dsp.csv").takeIf { it.isFile }?.useLines { s -> s.drop(1).forEach { line ->
            val c = line.split(','); if (c.size < 2) return@forEach; val g = a(c[0]); g.maxSqR2 = maxOf(g.maxSqR2, c[1].toDoubleOrNull() ?: 0.0)
        } }
        File(harvest, "cough_gate.csv").takeIf { it.isFile }?.let { f ->
            val pi = f.useLines { it.first().split(',').indexOf("pFused") }
            if (pi >= 0) f.useLines { s -> s.drop(1).forEach { line ->
                val c = line.split(','); val g = a(c[0]); g.maxFused = maxOf(g.maxFused, c.getOrNull(pi)?.toDoubleOrNull() ?: 0.0)
            } }
        }

        // coswara-matched cough-vs-breath/speech specialist (OOF scores; neutral 0.5 if absent)
        val spec = HashMap<String, Double>(90_000)
        File(harvest, "cough_specialist.csv").takeIf { it.isFile }?.useLines { s -> s.drop(1).forEach { line ->
            val c = line.split(','); if (c.size >= 2) c[1].toDoubleOrNull()?.let { spec[c[0]] = it }
        } }

        // assemble samples (clips with truth + whole-clip features)
        val ids = ArrayList<String>(); val xs = ArrayList<DoubleArray>(); val ys = ArrayList<Boolean>()
        val src = ArrayList<String>(); val grp = ArrayList<String>()
        for ((id, t) in truth) {
            val w = wc[id] ?: continue
            val g = agg[id] ?: Agg()
            xs += doubleArrayOf(w[0], w[1], w[2], w[3], w[4], w[5],
                g.maxHead, g.maxWav, g.maxFused, g.maxForest, if (g.hall) 1.0 else 0.0, ln(1.0 + g.nCand), g.maxSqR2,
                spec[id] ?: 0.5)
            ys += t.pos; ids += id; src += t.source
            grp += if (t.source.equals("Coswara", true)) "Coswara/${t.soundType}" else t.source
        }
        val nPos = ys.count { it }; val nNeg = ys.size - nPos
        println("=== CLIP-LEVEL META-FUSER over ${FEATURES.size} features ===")
        println("clips ${ys.size} (cough=$nPos, not_cough=$nNeg)")
        if (nPos < 100 || nNeg < 100) { println("insufficient data"); return }

        // 5-fold pooled out-of-fold predictions
        data class P(val truth: Boolean, val p: Double, val grp: String, val pos: Boolean)
        val preds = ArrayList<P>(ys.size)
        for (k in 0 until 5) {
            val tr = xs.indices.filter { it % 5 != k }; val te = xs.indices.filter { it % 5 == k }
            val model = WholeClipClassifier.train(tr.map { xs[it] to if (ys[it]) "cough" else "not_cough" }, classes)
            for (i in te) { val (lab, p) = model.predict(xs[i]); preds += P(ys[i], if (lab == "cough") p else 1 - p, grp[i], ys[i]) }
        }
        fun m(sel: (P) -> Boolean): Triple<Double, Double, Double> {  // recall, fp, precision
            var tp = 0; var fp = 0; var tn = 0; var fn = 0
            for (p in preds) { val c = sel(p); if (p.truth && c) tp++ else if (p.truth) fn++ else if (c) fp++ else tn++ }
            val rec = tp.toDouble() / (tp + fn).coerceAtLeast(1); val fpr = fp.toDouble() / (fp + tn).coerceAtLeast(1)
            val prec = tp.toDouble() / (tp + fp).coerceAtLeast(1); return Triple(rec, fpr, prec)
        }
        println("\n-- FUSED clip-gate threshold sweep --   recall    FP     precision")
        for (t in listOf(0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9)) {
            val (r, f, p) = m { it.p >= t }; println("  thr %.2f    %6.1f%%  %6.1f%%   %6.1f%%".format(t, r * 100, f * 100, p * 100))
        }
        // baselines on the SAME clips (feature idx: seg_maxFused=8, wc_forest=0)
        println("\n-- baselines on same clips --           recall    FP")
        fun base(name: String, sel: (Int) -> Boolean) {
            var tp = 0; var fp = 0; var tn = 0; var fn = 0
            for (i in xs.indices) { val c = sel(i); if (ys[i] && c) tp++ else if (ys[i]) fn++ else if (c) fp++ else tn++ }
            println("  %-22s %6.1f%%  %6.1f%%".format(name, 100.0 * tp / (tp + fn).coerceAtLeast(1), 100.0 * fp / (fp + tn).coerceAtLeast(1)))
        }
        base("fuser>=.7 (seg)") { xs[it][8] >= 0.7 }
        base("forest@wc>=.7") { xs[it][0] >= 0.7 }
        base("spec>=.5 (breath/sp)") { xs[it][13] >= 0.5 }
        base("UNION(both>=.7)") { xs[it][8] >= 0.7 || xs[it][0] >= 0.7 }
        base("INTERSECT(both>=.7)") { xs[it][8] >= 0.7 && xs[it][0] >= 0.7 }

        // per-group recall/FP for the fused gate at 0.5 and 0.7
        for (thr in listOf(0.5, 0.7)) {
            println("\n-- fused per-group @thr $thr (recall on POS groups, FP on NEG groups) --")
            preds.groupBy { it.grp }.toSortedMap().forEach { (g, ps) ->
                val pos = ps.first().pos; val hit = ps.count { it.p >= thr }.toDouble() / ps.size
                println("  %-34s %s %6.1f%%  (n=%d)".format(g, if (pos) "recall" else "FP    ", hit * 100, ps.size))
            }
        }

        // train final on all + save + per-clip dump
        val model = WholeClipClassifier.train(xs.indices.map { xs[it] to if (ys[it]) "cough" else "not_cough" }, classes)
        WholeClipClassifier.save(model, File(Workspace.dir("codebooks"), "cough_clipgate.json"))
        File(harvest, "cough_clipgate.csv").bufferedWriter().use { w ->
            w.write("id,source,truth,pClip,verdict@.5\n")
            for (i in ids.indices) { val (lab, p) = model.predict(xs[i]); val pc = if (lab == "cough") p else 1 - p
                w.write("${ids[i]},${src[i]},${if (ys[i]) "cough" else "not_cough"},${"%.3f".format(pc)},${if (pc >= 0.5) "cough" else "not_cough"}\n") }
        }
        println("\nsaved → data/codebooks/cough_clipgate.json + cough_harvest/cough_clipgate.csv")
    }
}
