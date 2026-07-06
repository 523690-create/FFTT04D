package com.example.FFTT04M.desktop

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * CWT-IMAGE cough/not-cough classifier over the harvested segments, compared head-to-head against the
 * "standard methodology" HuBERT cough head. Two INDEPENDENT representations (hand-crafted wavelet image vs
 * learned audio embedding) so their agreement is meaningful: where they agree we can auto-accept/reject,
 * where they disagree is the manual-review pile.
 *
 * Pipeline:
 *  1. TRAIN a linear cough/not-cough classifier on ALLDATA whole-clip CWT `.jpg` scalograms (labels from the
 *     source rec-type token; class-balanced; all cores). Independent of the harvest AND of the head.
 *  2. PREDICT P(cough) on every harvested segment's `.jpg` (all buckets, all cores, streamed).
 *  3. JOIN the HuBERT-head P(cough) from the `*_verified.csv` files (produced by verifyHarvest).
 *  4. COMPARE: wavelet↔head agreement + confusion; each vs filename-metadata truth, RAW and PURIFIED.
 *
 * THE CONTAMINATION GUARD (mixed clips are labelled 'cough' wholesale, so a pre/post-cough breath or word
 * chopped out of a cough clip inherits a false 'cough' label): metadata NEGATIVES are trusted (found_in_other
 * sources genuinely lack cough), metadata POSITIVES are NOT — a cough-bucket segment counts as a true cough
 * only when the OTHER method also calls it cough (independent corroboration, no self-circularity). The gap
 * between raw and purified metrics IS the contamination magnitude. Cough-bucket segments BOTH methods call
 * not-cough are flagged as suspected pre/post-cough phonemes for manual pruning.
 *
 * Run (after CWT images exist for the whole harvest + verifyHarvest -Dverify.threshold=0 on every bucket):
 *   ./gradlew :desktop:harvestClassify -Dhc.grid=32
 */
object HarvestClassifyCli {

    @JvmStatic
    fun main(args: Array<String>) {
        val repo = Workspace.repoRoot ?: File(".")
        val allData = File(System.getProperty("hc.alldata")?.takeIf { it.isNotBlank() } ?: File(repo, "ALLDATA").path)
        val harvest = File(System.getProperty("hc.harvest")?.takeIf { it.isNotBlank() } ?: File(repo, "cough_harvest").path)
        val g = System.getProperty("hc.grid")?.toIntOrNull() ?: 32
        val maxTrain = System.getProperty("hc.maxtrain")?.toIntOrNull() ?: 30000
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        if (!allData.isDirectory) { println("no ALLDATA dir: $allData"); return }
        if (!harvest.isDirectory) { println("no harvest dir: $harvest"); return }
        val dim = g * g * 3

        // ---- 1. TRAIN wavelet cough/not-cough on ALLDATA whole-clip scalograms -----------------------
        println("=== TRAIN wavelet cough/not-cough on ALLDATA scalograms (${g}x$g RGB, $cores cores) ===")
        val trainCand = (allData.listFiles { f -> f.isFile && f.extension.equals("wav", true) } ?: emptyArray())
            .mapNotNull { wav ->
                val id = wav.nameWithoutExtension
                val y = binLabel(id) ?: return@mapNotNull null
                val jpg = File(allData, "$id.jpg"); if (!jpg.isFile) null else Triple(id, jpg, y)
            }
        // class-balance up to maxTrain (equal cough / not-cough)
        val pos = trainCand.filter { it.third == 1 }; val neg = trainCand.filter { it.third == 0 }
        val per = (maxTrain / 2).coerceAtMost(minOf(pos.size, neg.size))
        if (per < 50) { println("too few labelled ALLDATA scalograms (pos=${pos.size}, neg=${neg.size}); is CWT built for ALLDATA?"); return }
        val trainSel = (pos.shuffled(java.util.Random(1)).take(per) + neg.shuffled(java.util.Random(2)).take(per))
        println("training on ${trainSel.size} scalograms (${per} cough / ${per} not-cough) of ${trainCand.size} labelled candidates")
        val X = loadFeaturesParallel(trainSel.map { it.second }, g, cores)
        val Y = trainSel.map { it.third }
        // drop any that failed to load (null row)
        val keep = X.indices.filter { X[it] != null }
        val xs = keep.map { X[it]!! }; val ys = keep.map { Y[it] }
        // z-normalise in place; keep mean/std for prediction
        val mean = DoubleArray(dim); for (f in xs) for (i in 0 until dim) mean[i] += f[i]; for (i in 0 until dim) mean[i] /= xs.size
        val std = DoubleArray(dim); for (f in xs) for (i in 0 until dim) { val e = f[i] - mean[i]; std[i] += e * e }
        for (i in 0 until dim) std[i] = sqrt(std[i] / xs.size).coerceAtLeast(1e-9)
        for (f in xs) for (i in 0 until dim) f[i] = (f[i] - mean[i]) / std[i]
        val (w, b) = trainLinearParallel(xs, ys, 2, iters = 400, cores = cores)
        // quick train-set fit sanity
        var tc = 0; for (i in xs.indices) { val p = softmax2(w, b, xs[i]); if ((if (p >= 0.5) 1 else 0) == ys[i]) tc++ }
        println("train-set fit accuracy ${pct(tc.toDouble() / xs.size)} (sanity, not held-out)")

        // ---- 2. PREDICT P(cough)_wavelet on every harvested segment jpg ------------------------------
        println("=== PREDICT wavelet P(cough) over harvested scalograms ===")
        val jpgs = harvest.walkTopDown().filter { it.isFile && it.extension.equals("jpg", true) }.toList()
        println("${jpgs.size} harvested scalograms found")
        val pWav = ConcurrentHashMap<String, Double>()
        val bucketOf = ConcurrentHashMap<String, String>()
        val done = AtomicInteger()
        run {
            val pool = Executors.newFixedThreadPool(cores)
            try {
                jpgs.map { jpg ->
                    pool.submit {
                        val id = jpg.nameWithoutExtension
                        val img = runCatching { ImageIO.read(jpg) }.getOrNull()
                        if (img != null) {
                            val v = downsample(img, g)
                            for (i in 0 until dim) v[i] = (v[i] - mean[i]) / std[i]
                            pWav[id] = softmax2(w, b, v)
                            bucketOf[id] = bucketName(jpg, harvest)
                        }
                        val n = done.incrementAndGet(); if (n % 5000 == 0 || n == jpgs.size) println("  predicted $n/${jpgs.size}")
                    }
                }.forEach { it.get() }
            } finally { pool.shutdown() }
        }

        // ---- 3. JOIN HuBERT-head P(cough) from *_verified.csv ----------------------------------------
        val pHead = HashMap<String, Double>()
        val csvs = harvest.listFiles { f -> f.isFile && f.name.endsWith("_verified.csv") } ?: emptyArray()
        for (csv in csvs) csv.useLines { lines ->
            lines.drop(1).forEach { ln ->
                val c = ln.lastIndexOf(','); if (c <= 0) return@forEach
                val id = ln.substring(0, c).removeSuffix(".wav"); val p = ln.substring(c + 1).toDoubleOrNull()
                if (p != null) pHead[id] = p
            }
        }
        println("=== JOIN: ${pWav.size} wavelet preds, ${pHead.size} head scores from ${csvs.size} verified.csv ===")

        // ---- 4. COMPARE — bag-aware. A 'cough' filename means a cough is PRESENT SOMEWHERE in the source
        //        clip, NOT that every extracted segment is a cough (multiple-instance positive bag). The
        //        speech/breathing/counting labels EXPLICITLY DENY cough → every segment from them is a hard
        //        per-segment negative. So: measure specificity on the hard negatives (trustworthy), and only
        //        BAG-level recall on the positives (the only positive truth we actually have). --------------
        val ids = pWav.keys.filter { pHead.containsKey(it) }
        if (ids.isEmpty()) { println("no clips have BOTH wavelet + head scores — run verifyHarvest -Dverify.threshold=0 on every bucket first"); return }
        val thr = 0.5
        val segRe = Regex("__cough\\d+_\\d+-\\d+ms$")                        // strip harvest suffix → source clip (bag)
        var agree = 0; val cm = Array(2) { IntArray(2) }                    // [wavCall][headCall], all clips
        var negN = 0; var wavFPneg = 0; var headFPneg = 0; var negAgree = 0 // hard negatives (label denies cough)
        var posSeg = 0; var wavPos = 0; var headPos = 0; var bothPos = 0    // segments inside positive (cough) bags
        val bagWav = HashMap<String, Boolean>(); val bagHead = HashMap<String, Boolean>()
        val contam = ArrayList<String>()
        val perBucket = HashMap<String, IntArray>()   // bucket -> [n, agree, wavCough, headCough]

        val out = File(harvest, "harvest_compare.csv").bufferedWriter()
        out.write("id,bucket,srcLabel,pWavelet,pHead,wavCall,headCall,agree,suspectPhoneme\n")
        for (id in ids.sorted()) {
            val pw = pWav.getValue(id); val ph = pHead.getValue(id)
            val wc = pw >= thr; val hc = ph >= thr
            val bucket = bucketOf[id] ?: "?"
            val meta = binLabel(id)   // 1 cough-present / 0 cough-denied / null unknown
            if (wc == hc) agree++
            cm[if (wc) 1 else 0][if (hc) 1 else 0]++
            val bs = perBucket.getOrPut(bucket) { IntArray(4) }
            bs[0]++; if (wc == hc) bs[1]++; if (wc) bs[2]++; if (hc) bs[3]++
            var suspect = false
            when (meta) {
                0 -> { negN++; if (wc) wavFPneg++; if (hc) headFPneg++; if (wc == hc) negAgree++ }
                1 -> {
                    posSeg++; if (wc) wavPos++; if (hc) headPos++; if (wc && hc) bothPos++
                    val src = id.replace(segRe, "")
                    bagWav[src] = (bagWav[src] ?: false) || wc
                    bagHead[src] = (bagHead[src] ?: false) || hc
                    suspect = bucket.contains("confirmed") && !wc && !hc
                    if (suspect) contam.add(id)
                }
            }
            out.write("$id,$bucket,${meta?.let { if (it == 1) "cough" else "not_cough" } ?: "unknown"}," +
                "${"%.3f".format(pw)},${"%.3f".format(ph)},$wc,$hc,${wc == hc},$suspect\n")
        }
        out.close()

        val n = ids.size
        println()
        println("=== WAVELET ↔ HuBERT-head per-segment AGREEMENT on $n harvested clips (P>=$thr) ===")
        println("  agreement ${pct(agree.toDouble() / n)}")
        println("  confusion [rows=wavelet, cols=head]   head:not-cough   head:cough")
        println("    wavelet not-cough                   ${cm[0][0].toString().padStart(12)}   ${cm[0][1].toString().padStart(10)}")
        println("    wavelet cough                       ${cm[1][0].toString().padStart(12)}   ${cm[1][1].toString().padStart(10)}")
        println()
        println("=== HARD NEGATIVES — segments from clips whose label DENIES cough (found_in_other): must be rejected ===")
        if (negN > 0) {
            println("  n=$negN   wavelet FP(calls cough) ${pct(wavFPneg.toDouble() / negN)}   head FP ${pct(headFPneg.toDouble() / negN)}   methods agree ${pct(negAgree.toDouble() / negN)}")
            println("  → lower FP = better; these are the DSP false positives both methods SHOULD filter out (the trustworthy metric)")
        } else println("  (none)")
        println()
        println("=== POSITIVE BAGS — clips whose label says a cough is PRESENT (cough_confirmed) ===")
        val nBags = bagWav.size
        if (nBags > 0) {
            val wavBagRec = bagWav.values.count { it }.toDouble() / nBags
            val headBagRec = bagHead.values.count { it }.toDouble() / nBags
            println("  $nBags source clips, $posSeg extracted segments")
            println("  BAG recall (≥1 segment called cough per clip):   wavelet ${pct(wavBagRec)}   head ${pct(headBagRec)}")
            println("  SEGMENT cough-call rate within positive bags:    wavelet ${pct(wavPos.toDouble() / posSeg)}   head ${pct(headPos.toDouble() / posSeg)}   both ${pct(bothPos.toDouble() / posSeg)}")
            println("  → segments NOT called cough are suspected pre/post-cough phonemes (the clip HAS a cough, just not in this slice)")
        } else println("  (none)")
        println()
        println("=== per-bucket ===")
        for ((bk, s) in perBucket.toSortedMap()) if (s[0] > 0)
            println("  ${bk.padEnd(26)} n=${s[0].toString().padStart(7)}  agree ${pct(s[1].toDouble() / s[0])}  wav→cough ${pct(s[2].toDouble() / s[0])}  head→cough ${pct(s[3].toDouble() / s[0])}")
        println()
        println("=== CONTAMINATION GUARD: ${contam.size} cough_confirmed segments BOTH methods call NOT-cough")
        println("    → suspected pre/post-cough phonemes (flagged suspectPhoneme=true in harvest_compare.csv)")
        contam.take(8).forEach { println("      $it") }
        println("=== wrote ${File(harvest, "harvest_compare.csv")} ===")
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /** Binary source-clip label from the merged filename (source__id__RECTYPE__…). null = unknown/ambiguous. */
    private fun binLabel(id: String): Int? {
        val parts = id.split("__"); val src = parts.getOrNull(0)?.lowercase() ?: ""
        val rec = parts.getOrNull(2)?.lowercase() ?: ""
        return when {
            "cough" in rec -> 1
            "breath" in rec || "vowel" in rec || "counting" in rec || "snor" in rec || "sneeze" in rec -> 0
            src == "urban8k" || src == "train" -> 0
            else -> null
        }
    }

    private fun bucketName(jpg: File, harvest: File): String {
        // first path segment under the harvest root (cough_confirmed / cough_found_in_other[/_rejected_lowP] / …)
        val rel = jpg.parentFile.relativeToOrNull(harvest)?.path ?: jpg.parentFile.name
        return rel.replace('\\', '/').ifEmpty { jpg.parentFile.name }
    }

    private fun loadFeaturesParallel(files: List<File>, g: Int, cores: Int): Array<DoubleArray?> {
        val out = arrayOfNulls<DoubleArray>(files.size)
        val pool = Executors.newFixedThreadPool(cores)
        try {
            files.indices.map { i -> pool.submit { runCatching { ImageIO.read(files[i]) }.getOrNull()?.let { out[i] = downsample(it, g) } } }
                .forEach { it.get() }
        } finally { pool.shutdown() }
        return out
    }

    private fun downsample(img: java.awt.image.BufferedImage, g: Int): DoubleArray {
        val small = java.awt.image.BufferedImage(g, g, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val gr = small.createGraphics()
        gr.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        gr.drawImage(img, 0, 0, g, g, null); gr.dispose()
        val v = DoubleArray(g * g * 3); var k = 0
        for (y in 0 until g) for (x in 0 until g) {
            val rgb = small.getRGB(x, y)
            v[k++] = ((rgb shr 16) and 0xFF) / 255.0; v[k++] = ((rgb shr 8) and 0xFF) / 255.0; v[k++] = (rgb and 0xFF) / 255.0
        }
        return v
    }

    /** P(class 1) from a 2-class linear softmax. */
    private fun softmax2(w: Array<DoubleArray>, b: DoubleArray, x: DoubleArray): Double {
        val l0 = run { var s = b[0]; val ww = w[0]; for (j in x.indices) s += ww[j] * x[j]; s }
        val l1 = run { var s = b[1]; val ww = w[1]; for (j in x.indices) s += ww[j] * x[j]; s }
        val mx = maxOf(l0, l1); val e0 = exp(l0 - mx); val e1 = exp(l1 - mx); return e1 / (e0 + e1)
    }

    /** Data-parallel batch-GD linear softmax (class-balanced + L2), sample loop fanned across [cores]. */
    private fun trainLinearParallel(xs: List<DoubleArray>, ys: List<Int>, nc: Int, iters: Int, cores: Int,
                                    lr: Double = 0.5, l2: Double = 0.02): Pair<Array<DoubleArray>, DoubleArray> {
        val f = xs[0].size
        val w = Array(nc) { DoubleArray(f) }; val b = DoubleArray(nc)
        val freq = IntArray(nc); for (y in ys) if (y in 0 until nc) freq[y]++
        val cw = DoubleArray(nc) { if (freq[it] > 0) xs.size.toDouble() / (nc * freq[it]) else 0.0 }
        val m = xs.size.toDouble()
        val chunks = xs.indices.chunked(((xs.size + cores - 1) / cores).coerceAtLeast(1))
        val gW = chunks.map { Array(nc) { DoubleArray(f) } }; val gB = chunks.map { DoubleArray(nc) }
        val pool = Executors.newFixedThreadPool(cores)
        try {
            repeat(iters) {
                chunks.indices.map { c ->
                    pool.submit {
                        val lw = gW[c]; val lb = gB[c]
                        for (r in lw) java.util.Arrays.fill(r, 0.0); java.util.Arrays.fill(lb, 0.0)
                        for (n in chunks[c]) {
                            val x = xs[n]; val yi = ys[n]; val wt = cw[yi]
                            val lg = DoubleArray(nc) { k -> var s = b[k]; val ww = w[k]; for (j in 0 until f) s += ww[j] * x[j]; s }
                            val mxx = lg.max(); var sum = 0.0; val p = DoubleArray(nc) { val e = exp(lg[it] - mxx); sum += e; e }; for (k in 0 until nc) p[k] /= sum
                            for (k in 0 until nc) { val err = (p[k] - if (k == yi) 1.0 else 0.0) * wt; lb[k] += err; val gg = lw[k]; for (j in 0 until f) gg[j] += err * x[j] }
                        }
                    }
                }.forEach { it.get() }
                for (k in 0 until nc) {
                    var db = 0.0; for (c in chunks.indices) db += gB[c][k]; b[k] -= lr * db / m
                    val ww = w[k]; for (j in 0 until f) { var dw = 0.0; for (c in chunks.indices) dw += gW[c][k][j]; ww[j] -= lr * (dw / m + l2 * ww[j]) }
                }
            }
        } finally { pool.shutdown() }
        return w to b
    }

    private fun pct(x: Double) = "%.1f%%".format(x * 100)
}
