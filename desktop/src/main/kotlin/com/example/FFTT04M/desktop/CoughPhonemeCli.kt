package com.example.FFTT04M.desktop

import com.example.FFTT04M.desktop.fractionation.HubertKMeansUnits
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * "Cough-hallmark phonemes": discover HuBERT acoustic units (180ms/90ms fixed-grid windows → HuBERT-768
 * mean-pool → k-means K units) from the harvest's CONSENSUS-labelled segments (both wavelet + head agree),
 * then rank each unit by how COUGH-SPECIFIC it is — frequent in coughs, rare in verified non-cough, and far
 * from every non-cough-dominated unit in embedding space. The top units are hallmarks: their mere presence
 * in a segment implies cough with high precision (a language-agnostic speech/breath rejector, answering #2).
 *
 * Honest eval: units are scored on a TRAIN half of the segments; the "≥1 hallmark unit ⇒ cough" detector is
 * measured on the held-out TEST half.
 *
 * Run (needs cough_harvest/harvest_compare.csv + HuBERT): ./gradlew :desktop:coughPhonemes -PuseOnnxGpu
 */
object CoughPhonemeCli {
    private const val SR = 44100
    private const val WIN_MS = 180
    private const val HOP_MS = 90

    @JvmStatic
    fun main(args: Array<String>) {
        val repo = Workspace.repoRoot ?: File(".")
        val harvest = File(System.getProperty("cp.harvest")?.takeIf { it.isNotBlank() } ?: File(repo, "cough_harvest").path)
        val perClass = System.getProperty("cp.perclass")?.toIntOrNull() ?: 6000
        val K = System.getProperty("cp.k")?.toIntOrNull() ?: 256
        val precThr = System.getProperty("cp.prec")?.toDoubleOrNull() ?: 0.90   // hallmark = cough-precision >= this
        val minSup = System.getProperty("cp.minsup")?.toIntOrNull() ?: 20       // min TRAIN windows to trust a unit
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val comp = File(harvest, "harvest_compare.csv")
        if (!comp.isFile) { println("no $comp — run harvestClassify first"); return }
        if (!HubertKMeansUnits.available) { println("HuBERT unavailable: ${HubertKMeansUnits.unavailableReason}"); return }

        // ---- consensus segments from harvest_compare.csv (both methods agree) ------------------------
        val cough = ArrayList<Pair<String, File>>(); val notc = ArrayList<Pair<String, File>>()
        comp.useLines { lines ->
            lines.drop(1).forEach { ln ->
                val c = ln.split(','); if (c.size < 7) return@forEach
                val id = c[0]; val bucket = c[1]; val wc = c[5] == "true"; val hc = c[6] == "true"
                val wav = File(harvest, "$bucket/$id.wav")
                if (wc && hc) cough.add(id to wav) else if (!wc && !hc) notc.add(id to wav)
            }
        }
        val rnd = java.util.Random(7)
        val cs = cough.shuffled(rnd).take(perClass); val ns = notc.shuffled(rnd).take(perClass)
        println("=== COUGH-PHONEME discovery: ${cs.size} consensus-cough + ${ns.size} consensus-not-cough segments, K=$K, ${WIN_MS}/${HOP_MS}ms windows, $cores cores ===")

        // ---- HuBERT window features (parallel; GPU serialises the embeds) -----------------------------
        // window rows: (vec768, isCough, segIndex). segIndex parity → train/test.
        class Row(val v: FloatArray, val cough: Boolean, val seg: Int)
        val rows = ConcurrentLinkedQueue<Row>()
        val segList = cs.map { it to true } + ns.map { it to false }
        val done = AtomicInteger()
        run {
            val pool = Executors.newFixedThreadPool(4)
            try {
                segList.mapIndexed { si, (item, isC) ->
                    pool.submit {
                        try {
                            val pcm = AudioDecoder.decode(item.second)?.also { rms(it) }
                            if (pcm != null && pcm.size >= SR / 16 && pcm.size <= SR * 8) {
                                val fe = HubertKMeansUnits.frameEmbeddings(pcm, SR)
                                if (fe != null && fe.isNotEmpty()) {
                                    val durMs = (pcm.size.toLong() * 1000 / SR).toInt(); val T = fe.size; val d = fe[0].size
                                    for ((sMs, eMs) in framesFor(durMs)) {
                                        val f0 = (sMs.toLong() * T / durMs).toInt().coerceIn(0, T - 1)
                                        val f1 = (eMs.toLong() * T / durMs).toInt().coerceIn(f0 + 1, T)
                                        val v = FloatArray(d); for (f in f0 until f1) { val fr = fe[f]; for (j in 0 until d) v[j] += fr[j] }
                                        val inv = 1f / (f1 - f0); for (j in 0 until d) v[j] *= inv
                                        rows.add(Row(v, isC, si))
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                        val n = done.incrementAndGet(); if (n % 1000 == 0 || n == segList.size) println("  embedded $n/${segList.size} segments · ${rows.size} windows")
                    }
                }.forEach { it.get() }
            } finally { pool.shutdown() }
        }
        val R = rows.toList()
        if (R.size < K * 4) { println("too few windows (${R.size})"); return }
        val d = R[0].v.size; val m = R.size
        println("${R.size} windows, dim $d — z-norming + k-means…")

        // ---- z-norm (into DoubleArray for k-means) ----------------------------------------------------
        val mean = DoubleArray(d); for (r in R) for (j in 0 until d) mean[j] += r.v[j]; for (j in 0 until d) mean[j] /= m
        val std = DoubleArray(d); for (r in R) for (j in 0 until d) { val e = r.v[j] - mean[j]; std[j] += e * e }
        for (j in 0 until d) std[j] = sqrt(std[j] / m).coerceAtLeast(1e-9)
        val X = Array(m) { i -> DoubleArray(d) { j -> (R[i].v[j] - mean[j]) / std[j] } }

        // ---- k-means (k-means++ init, parallel Lloyd) -------------------------------------------------
        val (cent, assign) = kmeans(X, K, iters = 25, cores = cores)

        // ---- per-unit stats on TRAIN windows (seg parity 0), detector eval on TEST (parity 1) ---------
        val cwTr = IntArray(K); val nwTr = IntArray(K)     // train window counts per unit
        for (i in 0 until m) if (R[i].seg % 2 == 0) { if (R[i].cough) cwTr[assign[i]]++ else nwTr[assign[i]]++ }
        val totCoughTr = cwTr.sum().toDouble().coerceAtLeast(1.0); val totNotTr = nwTr.sum().toDouble().coerceAtLeast(1.0)
        // per-unit: cough precision, lift (P(unit|cough)/P(unit|notcough))
        data class U(val id: Int, val cw: Int, val nw: Int, val prec: Double, val lift: Double)
        val units = (0 until K).map { u ->
            val cw = cwTr[u]; val nw = nwTr[u]
            val prec = if (cw + nw > 0) cw.toDouble() / (cw + nw) else 0.0
            val lift = ((cw + 1) / (totCoughTr + 1)) / ((nw + 1) / (totNotTr + 1))
            U(u, cw, nw, prec, lift)
        }
        val hallmark = units.filter { it.cw + it.nw >= minSup && it.prec >= precThr }.map { it.id }.toHashSet()
        val notDom = units.filter { it.cw + it.nw >= minSup && it.prec < 0.5 }.map { it.id }   // non-cough-dominated units

        // distance of each hallmark centroid to the nearest non-cough-dominated unit centroid
        fun dist(a: DoubleArray, b: DoubleArray): Double { var s = 0.0; for (j in a.indices) { val e = a[j] - b[j]; s += e * e }; return sqrt(s) }
        val hallmarkRanked = units.filter { it.id in hallmark }.map { u ->
            val dn = notDom.minOfOrNull { dist(cent[u.id], cent[it]) } ?: Double.NaN
            Triple(u, dn, u.cw + u.nw)
        }.sortedWith(compareByDescending<Triple<U, Double, Int>> { it.first.prec }.thenByDescending { it.second })

        // ---- detector on TEST segments: does the segment contain >=1 hallmark unit? -------------------
        val segCough = HashMap<Int, Boolean>(); val segHasHall = HashMap<Int, Boolean>()
        for (i in 0 until m) if (R[i].seg % 2 == 1) {
            segCough[R[i].seg] = R[i].cough
            if (assign[i] in hallmark) segHasHall[R[i].seg] = true
        }
        var tp = 0; var fp = 0; var tn = 0; var fn = 0
        for ((seg, isC) in segCough) { val hit = segHasHall[seg] == true; when { hit && isC -> tp++; hit && !isC -> fp++; !hit && isC -> fn++; else -> tn++ } }
        val prec = tp.toDouble() / (tp + fp).coerceAtLeast(1); val rec = tp.toDouble() / (tp + fn).coerceAtLeast(1)

        println()
        println("=== ${hallmark.size} HALLMARK units (train cough-precision >= ${pct(precThr)}, support >= $minSup) of $K ===")
        println("  ${"unit".padEnd(6)} ${"coughWin".padStart(9)} ${"notWin".padStart(7)} ${"precision".padStart(10)} ${"lift".padStart(7)} ${"dist→notcough".padStart(14)}")
        for ((u, dn, _) in hallmarkRanked.take(25))
            println("  ${("#" + u.id).padEnd(6)} ${u.cw.toString().padStart(9)} ${u.nw.toString().padStart(7)} ${pct(u.prec).padStart(10)} ${"%.1f".format(u.lift).padStart(7)} ${"%.2f".format(dn).padStart(14)}")
        if (hallmarkRanked.size > 25) println("  … and ${hallmarkRanked.size - 25} more")
        println()
        println("=== HALLMARK DETECTOR on held-out TEST segments (segment has >=1 hallmark unit ⇒ cough) ===")
        println("  precision ${pct(prec)}  recall ${pct(rec)}  (tp=$tp fp=$fp tn=$tn fn=$fn)")
        println("  → high precision = presence of a hallmark unit reliably means cough (the specific-phoneme detector / speech rejector)")

        // ---- write full per-unit table -------------------------------------------------------------
        File(harvest, "cough_phonemes.csv").bufferedWriter().use { w ->
            w.write("unit,coughWin,notCoughWin,coughPrecision,lift,isHallmark\n")
            for (u in units.sortedByDescending { it.prec })
                w.write("${u.id},${u.cw},${u.nw},${"%.3f".format(u.prec)},${"%.2f".format(u.lift)},${u.id in hallmark}\n")
        }
        println("=== wrote ${File(harvest, "cough_phonemes.csv")} (all $K units, ranked) ===")

        // ---- optional: export the deployable hallmark codebook (norm + all centroids + flags) ---------
        if (System.getProperty("cp.export")?.toBoolean() == true) {
            val cb = File(Workspace.dir("codebooks"), "cough_hallmark_units.json")
            val unitsJson = units.map { u ->
                mapOf("id" to u.id, "isHallmark" to (u.id in hallmark), "coughPrecision" to u.prec,
                    "coughWin" to u.cw, "notWin" to u.nw, "lift" to u.lift, "centroid" to cent[u.id].toList())
            }
            val outMap = mapOf(
                "featureType" to "hubert768", "winMs" to WIN_MS, "hopMs" to HOP_MS, "k" to K,
                "nHallmark" to hallmark.size, "testPrecision" to prec, "testRecall" to rec,
                "rule" to "per 180/90ms window: HuBERT-768 mean-pool → (v-mean)/std → nearest centroid; " +
                    "segment=cough if ANY window's nearest centroid isHallmark",
                "norm" to mapOf("mean" to mean.toList(), "std" to std.toList()),
                "units" to unitsJson)
            cb.writeText(com.google.gson.GsonBuilder().create().toJson(outMap))
            println("=== exported ${hallmark.size} hallmark / $K units → $cb ===")
        }
    }

    private fun framesFor(durMs: Int): List<Pair<Int, Int>> {
        if (durMs <= WIN_MS) return if (durMs > 0) listOf(0 to durMs) else emptyList()
        val out = ArrayList<Pair<Int, Int>>(); var s = 0
        while (s < durMs) { val e = (s + WIN_MS).coerceAtMost(durMs); if (e - s >= WIN_MS / 2) out.add(s to e); if (e >= durMs) break; s += HOP_MS }
        return out
    }

    private fun kmeans(X: Array<DoubleArray>, K: Int, iters: Int, cores: Int): Pair<Array<DoubleArray>, IntArray> {
        val m = X.size; val d = X[0].size; val rnd = java.util.Random(42)
        // k-means++ init
        val cent = Array(K) { DoubleArray(d) }
        cent[0] = X[rnd.nextInt(m)].copyOf()
        val dmin = DoubleArray(m) { Double.MAX_VALUE }
        for (c in 1 until K) {
            var sum = 0.0
            for (i in 0 until m) { val dd = dist2(X[i], cent[c - 1]); if (dd < dmin[i]) dmin[i] = dd; sum += dmin[i] }
            var t = rnd.nextDouble() * sum; var pick = 0
            for (i in 0 until m) { t -= dmin[i]; if (t <= 0) { pick = i; break } }
            cent[c] = X[pick].copyOf()
        }
        val assign = IntArray(m)
        val pool = Executors.newFixedThreadPool(cores)
        val chunks = (0 until m).chunked(((m + cores - 1) / cores).coerceAtLeast(1))
        try {
            repeat(iters) {
                chunks.map { ch -> pool.submit { for (i in ch) { var best = 0; var bd = Double.MAX_VALUE; for (c in 0 until K) { val dd = dist2(X[i], cent[c]); if (dd < bd) { bd = dd; best = c } }; assign[i] = best } } }.forEach { it.get() }
                val sums = Array(K) { DoubleArray(d) }; val cnt = IntArray(K)
                for (i in 0 until m) { val a = assign[i]; cnt[a]++; val s = sums[a]; val x = X[i]; for (j in 0 until d) s[j] += x[j] }
                for (c in 0 until K) if (cnt[c] > 0) { val s = sums[c]; for (j in 0 until d) cent[c][j] = s[j] / cnt[c] }
            }
        } finally { pool.shutdown() }
        return cent to assign
    }

    private fun dist2(a: DoubleArray, b: DoubleArray): Double { var s = 0.0; for (j in a.indices) { val e = a[j] - b[j]; s += e * e }; return s }
    private fun rms(pcm: FloatArray, target: Float = 0.1f) { var s = 0.0; for (x in pcm) s += x.toDouble() * x; val r = sqrt(s / pcm.size.coerceAtLeast(1)); if (r > 1e-5) { val g = (target / r).toFloat(); for (i in pcm.indices) pcm[i] *= g } }
    private fun pct(x: Double) = "%.1f%%".format(x * 100)
}
