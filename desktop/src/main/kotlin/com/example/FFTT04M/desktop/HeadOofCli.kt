package com.example.FFTT04M.desktop

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * PARTICIPANT-FOLDED out-of-fold HuBERT cough head (measurement only). The clip gate's `seg_maxHead` input comes from
 * `cough_head_ALLDATA.json`, a 768→2 logistic head trained on ALLDATA segments (`segemb_ALLDATA.bin`) that overlap the
 * gate's own clips. 2026-11-27 put the leak of every upstream model except this head (and the wavelet) at ≈1 pp; this
 * closes the head's share. CPU only: it uses the cached embeddings and never runs HuBERT.
 *
 *  - training rows: every `segemb_ALLDATA.bin` entry (`<clipId>#<win>`) whose clip has an ALLDATA truth label
 *    (CoughTruth from metadata.csv, the SAME truth the gate uses — not UnsupervisedCluster's filename rule, so this is a
 *    same-shape refit, not a byte-for-byte copy of the shipped head; `maxHeadFull` = the all-folds refit separates the
 *    label/sample change from the OOF effect);
 *  - folds: `ClipGateCli.groupKey` hashed % 5, identical to `clipGate -Dclip.folds=participant` and `:desktop:forestOof`;
 *  - scoring: streams `harvestemb_cough_{confirmed,found_in_other,unknown}.bin` and scores each harvest segment with the
 *    head whose fold excludes the segment's parent clip's participant (all-folds head if the parent has no metadata).
 *
 * Writes ONLY `<out>/head_oof.csv` (id,maxHeadOOF,maxHeadFull,maxHeadShipped,nSeg,fold,truth) per parent clip. Feed it
 * to `:desktop:clipGate -Dclip.headOof=<csv>` (replaces seg_maxHead). Touches no codebook, harvest CSV or bundled model.
 *
 * Run: ./gradlew :desktop:headOof -Deval.alldata=D:/AndroidProjects/ALLDATA -Doof.out=D:/AndroidProjects/data/_head_oof_20261128
 */
object HeadOofCli {

    private val SUFFIX = Regex("__cough\\d+_\\d+-\\d+ms$")

    private class Head(val mean: DoubleArray, val std: DoubleArray, val w: Array<DoubleArray>, val b: DoubleArray) {
        fun p(v: DoubleArray): Double {
            val z = DoubleArray(v.size) { (v[it] - mean[it]) / std[it] }
            val l0 = b[0] + dot(w[0], z); val l1 = b[1] + dot(w[1], z)
            return 1.0 / (1.0 + exp(l0 - l1))
        }
        private fun dot(a: DoubleArray, z: DoubleArray): Double { var s = 0.0; for (j in z.indices) s += a[j] * z[j]; return s }
    }

    private fun fit(xs: List<DoubleArray>, ys: List<Int>): Head {
        val d = xs[0].size; val n = xs.size
        val mean = DoubleArray(d); for (v in xs) for (i in 0 until d) mean[i] += v[i]
        for (i in 0 until d) mean[i] /= n
        val std = DoubleArray(d); for (v in xs) for (i in 0 until d) { val e = v[i] - mean[i]; std[i] += e * e }
        for (i in 0 until d) std[i] = sqrt(std[i] / n).coerceAtLeast(1e-9)
        val z = xs.map { v -> DoubleArray(d) { (v[it] - mean[it]) / std[it] } }
        val (w, b) = SoftmaxLR.train(z, ys, 2)
        return Head(mean, std, w, b)
    }

    /** Streams one `writeUTF(key) writeInt(d) d×writeFloat` cache file. */
    private fun stream(f: File, each: (String, DoubleArray) -> Unit) {
        if (!f.isFile) { println("  (missing $f)"); return }
        DataInputStream(f.inputStream().buffered(1 shl 20)).use { din ->
            while (true) {
                val key = try { din.readUTF() } catch (e: EOFException) { break }
                val d = din.readInt(); val v = DoubleArray(d) { din.readFloat().toDouble() }
                each(key, v)
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val repo = Workspace.repoRoot ?: File(".")
        val alldata = File(System.getProperty("eval.alldata")?.takeIf { it.isNotBlank() } ?: File(repo, "ALLDATA").path)
        val harvest = File(System.getProperty("eval.harvest")?.takeIf { it.isNotBlank() } ?: File(repo, "cough_harvest").path)
        val out = File(System.getProperty("oof.out")?.takeIf { it.isNotBlank() }
            ?: File(repo, "data/_head_oof_" + java.time.LocalDate.now().toString().replace("-", "")).path).apply { mkdirs() }
        val meta = File(alldata, "metadata.csv")
        if (!meta.isFile) { println("missing $meta"); return }
        val cb = Workspace.dir("codebooks")

        // clip → (fold, truth); truth null = SKIP (still gets a fold so it is scored out-of-fold)
        val foldOf = HashMap<String, Int>(240_000); val truth = HashMap<String, Boolean>(90_000)
        meta.useLines { seq ->
            seq.drop(1).forEach { line ->
                val c = line.split(','); if (c.size < 5) return@forEach
                val id = c[0].trim().removeSuffix(".wav")
                foldOf[id] = (ClipGateCli.groupKey(c[1].trim(), c[2].trim()).hashCode() and 0x7fffffff) % 5
                val t = CoughTruth.fromIsCough(CoughTruth.parseIsCough(c[4]))
                if (t != CoughTruth.Truth.SKIP) truth[id] = t == CoughTruth.Truth.POS
            }
        }
        println("=== HEAD OOF (participant folds) — ${foldOf.size} metadata clips, ${truth.size} labelled → $out ===")

        // ---- training segments (dedupe by key, last write wins like the append cache) ------------------------
        val train = LinkedHashMap<String, DoubleArray>()
        stream(File(cb, "segemb_ALLDATA.bin")) { k, v -> train[k] = v }
        val rows = train.entries.mapNotNull { (k, v) ->
            val clip = k.substringBefore('#'); val t = truth[clip] ?: return@mapNotNull null
            Triple(v, if (t) 1 else 0, foldOf.getValue(clip))
        }
        println("training segments: ${train.size} cached, ${rows.size} with ALLDATA truth (cough=${rows.count { it.second == 1 }}), " +
            "clips ${train.keys.map { it.substringBefore('#') }.toSet().size}")
        if (rows.count { it.second == 1 } < 50 || rows.count { it.second == 0 } < 50) { println("insufficient training rows"); return }

        val t0 = System.nanoTime()
        val heads = (0 until 5).map { k ->
            val tr = rows.filter { it.third != k }
            fit(tr.map { it.first }, tr.map { it.second }).also {
                println("  fold $k head: trained on ${tr.size}, ${"%.0f".format((System.nanoTime() - t0) / 1e9)}s")
            }
        }
        val full = fit(rows.map { it.first }, rows.map { it.second })
        // in-domain sanity: segment-level OOF accuracy on the training rows themselves
        run {
            var ok = 0; for (r in rows) if ((heads[r.third].p(r.first) >= 0.5) == (r.second == 1)) ok++
            println("  segment-level participant-OOF accuracy on training rows: ${"%.1f".format(100.0 * ok / rows.size)}%")
        }

        // ---- score every harvest segment, aggregate max per parent clip -------------------------------------
        class Agg { var oof = 0.0; var full = 0.0; var shipped = 0.0; var n = 0 }
        val agg = HashMap<String, Agg>(90_000)
        var seen = 0; var noMeta = 0
        for (bucket in listOf("cough_confirmed", "cough_found_in_other", "cough_unknown")) {
            val before = seen
            stream(File(cb, "harvestemb_$bucket.bin")) { name, v ->
                val parent = name.removeSuffix(".wav").replace(SUFFIX, "")
                val k = foldOf[parent]
                if (k == null) noMeta++
                val g = agg.getOrPut(parent) { Agg() }
                g.oof = maxOf(g.oof, (if (k == null) full else heads[k]).p(v)); g.full = maxOf(g.full, full.p(v)); g.n++
                seen++
            }
            println("  scored $bucket: ${seen - before} segments")
        }
        // shipped head's per-segment pHead from harvest_compare.csv (same max-aggregation the gate does)
        var cmpRows = 0; var cmpNoEmb = 0
        File(harvest, "harvest_compare.csv").takeIf { it.isFile }?.useLines { s -> s.drop(1).forEach { line ->
            val c = line.split(','); if (c.size < 7) return@forEach
            val g = agg[c[0].replace(SUFFIX, "")]; cmpRows++
            if (g == null) cmpNoEmb++ else g.shipped = maxOf(g.shipped, c[4].toDoubleOrNull() ?: 0.0)
        } }
        println("harvest segments scored $seen (parent without metadata: $noMeta) · parents ${agg.size} · " +
            "harvest_compare rows $cmpRows (parent without any cached embedding: $cmpNoEmb)")

        File(out, "head_oof.csv").bufferedWriter().use { w ->
            w.write("id,maxHeadOOF,maxHeadFull,maxHeadShipped,nSeg,fold,truth\n")
            for (id in agg.keys.sorted()) { val g = agg.getValue(id)
                w.write("$id,${"%.4f".format(g.oof)},${"%.4f".format(g.full)},${"%.4f".format(g.shipped)},${g.n},${foldOf[id] ?: -1}," +
                    "${truth[id]?.let { if (it) "cough" else "not_cough" } ?: ""}\n") }
        }

        // clip-level seg_maxHead alone, on labelled clips that have harvest segments
        val lab = agg.entries.filter { truth.containsKey(it.key) }
        fun rate(sel: (Agg) -> Double, thr: Double): Pair<Double, Double> {
            var tp = 0; var fn = 0; var fp = 0; var tn = 0
            for ((id, g) in lab) { val c = sel(g) >= thr; if (truth.getValue(id)) { if (c) tp++ else fn++ } else { if (c) fp++ else tn++ } }
            return 100.0 * tp / (tp + fn).coerceAtLeast(1) to 100.0 * fp / (fp + tn).coerceAtLeast(1)
        }
        println("\n-- clip-level seg_maxHead alone (${lab.size} labelled clips with harvest segments) --")
        println("            OOF rec/FP      full-refit rec/FP   shipped rec/FP")
        for (t in listOf(0.5, 0.7, 0.8, 0.9)) {
            val (ro, fo) = rate({ it.oof }, t); val (rf, ff) = rate({ it.full }, t); val (rs, fs) = rate({ it.shipped }, t)
            println("  thr %.2f   %5.1f / %5.1f     %5.1f / %5.1f       %5.1f / %5.1f".format(t, ro, fo, rf, ff, rs, fs))
        }
        println("\nwrote ${File(out, "head_oof.csv")} — measurement only, nothing else touched")
    }
}
