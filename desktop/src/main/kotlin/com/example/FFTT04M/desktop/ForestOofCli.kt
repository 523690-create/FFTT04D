package com.example.FFTT04M.desktop

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * PARTICIPANT-FOLDED out-of-fold whole-clip forest (measurement only). The clip gate's `wc_forest` input comes from the
 * bundled CoughForest, which was fit on sources that overlap ALLDATA (COUGHVID, ESC-50, UrbanSound8K), so the
 * 2026-11-26 ablation could only BOUND its leak (≤7.4 pp recall / ≤2 pp FP @thr .7). This refits a CoughForest of
 * the same shape (WholeClipFeatures → 150 trees, depth 14) on ALLDATA labels, 5 folds grouped by the SAME key and
 * hash as `ClipGateCli -Dclip.folds=participant`, and writes each clip's OOF P(cough) so the gate can be re-run with
 * a leak-free forest input (`-Dclip.forestOof=<csv>`).
 *
 * Writes ONLY into the output dir (default data/_forest_oof_<today>): `wc_features.csv` (cache; re-used if present)
 * and `forest_oof.csv` (id,pForestOOF,pForestBundled,fold,truth). Touches no codebook, harvest CSV or bundled model.
 *
 * Run: ./gradlew :desktop:forestOof -Deval.alldata=D:/AndroidProjects/ALLDATA -Doof.out=D:/AndroidProjects/data/_forest_oof_20261127
 */
object ForestOofCli {

    @JvmStatic
    fun main(args: Array<String>) {
        val repo = Workspace.repoRoot ?: File(".")
        val alldata = File(System.getProperty("eval.alldata")?.takeIf { it.isNotBlank() } ?: File(repo, "ALLDATA").path)
        val out = File(System.getProperty("oof.out")?.takeIf { it.isNotBlank() }
            ?: File(repo, "data/_forest_oof_" + java.time.LocalDate.now().toString().replace("-", "")).path).apply { mkdirs() }
        val meta = File(alldata, "metadata.csv")
        if (!meta.isFile) { println("missing $meta"); return }
        val bundled = CoughForest.loadBundled()

        data class Clip(val id: String, val file: File, val pos: Boolean, val who: String, val source: String)
        val clips = ArrayList<Clip>(80_000)
        meta.useLines { seq ->
            seq.drop(1).forEach { line ->
                val c = line.split(','); if (c.size < 5) return@forEach
                val t = CoughTruth.fromIsCough(CoughTruth.parseIsCough(c[4]))
                if (t == CoughTruth.Truth.SKIP) return@forEach
                val name = c[0].trim(); val f = File(alldata, name); if (!f.isFile) return@forEach
                clips += Clip(name.removeSuffix(".wav"), f, t == CoughTruth.Truth.POS, ClipGateCli.groupKey(c[1].trim(), c[2].trim()), c[1].trim())
            }
        }
        println("=== FOREST OOF (participant folds) — ${clips.size} labelled clips → $out ===")

        // ---- whole-clip features (cached) ------------------------------------------------------------------
        val cache = File(out, "wc_features.csv")
        val feats = ConcurrentHashMap<String, DoubleArray>(90_000)
        if (cache.isFile) {
            cache.useLines { s -> s.drop(1).forEach { l -> val c = l.split(','); feats[c[0]] = DoubleArray(c.size - 1) { c[it + 1].toDouble() } } }
            println("feature cache: ${feats.size} rows from $cache")
        } else {
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val pool = Executors.newFixedThreadPool(cores); val done = AtomicInteger(); val t0 = System.nanoTime()
            try {
                clips.map { cl -> pool.submit {
                    try {
                        val pcm = if (cl.file.length() > 50_000_000L) null else AudioDecoder.decode(cl.file)
                        if (pcm != null && pcm.isNotEmpty()) feats[cl.id] = WholeClipFeatures.extract(pcm, 44100)
                    } catch (e: Exception) { System.err.println("feat ${cl.id}: ${e.message}") }
                    val d = done.incrementAndGet()
                    if (d % 5000 == 0 || d == clips.size) println("  features $d/${clips.size} · ${"%.0f".format(d / ((System.nanoTime() - t0) / 1e9))} clip/s")
                } }.forEach { it.get() }
            } finally { pool.shutdown() }
            cache.bufferedWriter().use { w ->
                w.write("id," + WholeClipFeatures.names.joinToString(",") + "\n")
                for (id in feats.keys.sorted()) { w.write(id); for (v in feats.getValue(id)) { w.write(","); w.write("%.6g".format(v)) }; w.write("\n") }
            }
        }

        val use = clips.filter { feats.containsKey(it.id) }
        val X = Array(use.size) { feats.getValue(use[it].id) }
        val y = IntArray(use.size) { if (use[it].pos) 1 else 0 }
        val nF = X[0].size
        // identical fold rule to ClipGateCli -Dclip.folds=participant
        val fold = IntArray(use.size) { (use[it].who.hashCode() and 0x7fffffff) % 5 }
        println("clips with features ${use.size} (cough=${y.sum()}, not=${use.size - y.sum()}), groups ${use.map { it.who }.toSet().size}")

        // ---- 5 folds in parallel (CoughForest.train is single-threaded) ------------------------------------
        val oof = DoubleArray(use.size) { Double.NaN }
        val t1 = System.nanoTime()
        val pool = Executors.newFixedThreadPool(5)
        try {
            (0 until 5).map { k -> pool.submit {
                val tr = use.indices.filter { fold[it] != k }
                val model = CoughForest.train(Array(tr.size) { X[tr[it]] }, IntArray(tr.size) { y[tr[it]] }, nF, 0.5, seed = 42L + k)
                for (i in use.indices) if (fold[i] == k) oof[i] = model.coughProb(X[i])
                println("  fold $k: trained on ${tr.size}, ${"%.0f".format((System.nanoTime() - t1) / 1e9)}s")
            } }.forEach { it.get() }
        } finally { pool.shutdown() }

        val pB = DoubleArray(use.size) { bundled?.coughProb(X[it]) ?: Double.NaN }
        File(out, "forest_oof.csv").bufferedWriter().use { w ->
            w.write("id,pForestOOF,pForestBundled,fold,truth\n")
            for (i in use.indices) w.write("${use[i].id},${"%.4f".format(oof[i])},${"%.4f".format(pB[i])},${fold[i]},${if (y[i] == 1) "cough" else "not_cough"}\n")
        }

        fun rate(p: DoubleArray, thr: Double, sel: (Int) -> Boolean = { true }): Pair<Double, Double> {
            var tp = 0; var fn = 0; var fp = 0; var tn = 0
            for (i in use.indices) if (sel(i)) { val c = p[i] >= thr; if (y[i] == 1) { if (c) tp++ else fn++ } else { if (c) fp++ else tn++ } }
            return 100.0 * tp / (tp + fn).coerceAtLeast(1) to 100.0 * fp / (fp + tn).coerceAtLeast(1)
        }
        println("\n-- whole-clip forest alone, same clips --   OOF rec/FP      bundled rec/FP (in-sample-ish)")
        for (t in listOf(0.3, 0.5, 0.7, 0.8)) {
            val (ro, fo) = rate(oof, t); val (rb, fb) = rate(pB, t)
            println("  thr %.2f   %6.1f / %5.1f      %6.1f / %5.1f".format(t, ro, fo, rb, fb))
        }
        println("\n-- per source @thr .5 (recall if mostly cough, else FP) --   OOF      bundled")
        use.indices.groupBy { use[it].source }.toSortedMap().forEach { (s, ix) ->
            val set = ix.toHashSet(); val pos = ix.count { y[it] == 1 } * 2 > ix.size
            val (ro, fo) = rate(oof, 0.5) { it in set }; val (rb, fb) = rate(pB, 0.5) { it in set }
            println("  %-14s %s  %6.1f%%  %6.1f%%  (n=%d)".format(s, if (pos) "recall" else "FP    ", if (pos) ro else fo, if (pos) rb else fb, ix.size))
        }
        println("\nwrote ${File(out, "forest_oof.csv")} — measurement only, nothing else touched")
    }
}
