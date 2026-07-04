package com.example.FFTT04M.desktop

import com.example.FFTT04M.desktop.fractionation.HubertKMeansUnits
import com.google.gson.Gson
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.util.Random
import javax.imageio.ImageIO
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Unsupervised cluster discovery over **whole-clip HuBERT embeddings** — the "throw away the labels and
 * see if the sounds self-assemble" experiment. For a corpus it: mean-pools each clip's HuBERT frames to
 * one 768-vec (cached on disk, so ALLDATA's big pass is one-time), z-normalises, k-means over a sweep of
 * k, and — using the labels ONLY afterward, on the labelled subset — scores each k with purity / NMI /
 * ARI and prints a cluster×label contingency matrix. Renders a PCA-2D scatter coloured by discovered
 * cluster. Answers: do the clusters line up with our taxonomy, merge some labels, or split others?
 *
 * Run:  ./gradlew :desktop:unsupervisedCluster -Dcluster.corpus=p3 -PuseOnnxGpu
 *       ./gradlew :desktop:unsupervisedCluster -Dcluster.corpus=ALLDATA -PuseOnnxGpu
 */
object UnsupervisedCluster {
    private const val SR = 44100
    private val KS = (System.getProperty("cluster.ks")?.takeIf { it.isNotBlank() } ?: "5,8,10,12,15,20")
        .split(",").mapNotNull { it.trim().toIntOrNull() }

    @JvmStatic
    fun main(args: Array<String>) {
        // NOTE: HuBERT is loaded LAZILY (only if there are un-cached clips to embed) — a cache-hit run
        // never touches the 377 MB model / CUDA context, so it stays light and can't OOM the machine.
        val corpus = args.getOrNull(0)?.takeIf { it.isNotBlank() }
            ?: System.getProperty("cluster.corpus")?.takeIf { it.isNotBlank() } ?: "p3"
        val repo = Workspace.repoRoot ?: File(".")
        val corpusDir = File(repo, corpus)
        if (!corpusDir.isDirectory) { println("no corpus dir: $corpusDir"); return }
        println("=== Unsupervised clustering: $corpus (whole-clip HuBERT) ===")

        val wavs = corpusDir.walkTopDown().filter { it.isFile && it.extension.equals("wav", true) }
            .associateBy { it.nameWithoutExtension }
        println("clips: ${wavs.size}")
        // Label each clip for SCORING ONLY: manual comment (cleaned + bronchitis date-split) wins, else
        // the filename auto-label (coswara/urban8k/train/esc50 → voice/noise/…). Clips with neither are
        // clustered but excluded from the label-agreement scores.
        val manual = loadLabels()
        val labels = HashMap<String, String>()
        for (id in wavs.keys) (manual[id] ?: AutoLabel.forId(id)?.let { clean(it) })?.let { labels[id] = it }

        // -Dcluster.segbinary=true → chop clips into short windows and measure a segment-level
        // cough/not-cough discriminator (handles long recordings; does its own bounded embedding).
        if (System.getProperty("cluster.segbinary")?.toBoolean() == true) { evalSegmentBinary(corpus, wavs, labels); return }

        // ---- embeddings (cached on disk; resumable append) ----
        val embAll = computeEmbeddings(corpus, wavs)
        // -Dcluster.binary=true → measure a purpose-built cough/not-cough discriminator instead of clustering.
        if (System.getProperty("cluster.binary")?.toBoolean() == true) { evalBinary(embAll, labels); return }
        if (embAll.size < KS.max()) { println("too few embeddings (${embAll.size})"); return }
        val d = embAll.values.first().size

        // Subsample for clustering to BOUND MEMORY — the full 76k×768 matrix in an uncapped heap once
        // exhausted RAM and took the machine down. Keep ALL labelled clips + a deterministic random fill
        // up to MAX_N (a 30k sample is ample for discovering cluster structure).
        val maxN = System.getProperty("cluster.max")?.toIntOrNull() ?: 30000
        // -Dcluster.exclude=voice,noise → cluster only clips whose (kept) label survives the filter, so
        // the rare cough subtypes aren't drowned by the voice/noise mega-classes.
        val exclude = (System.getProperty("cluster.exclude") ?: "").split(",")
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val allIds = if (exclude.isEmpty()) embAll.keys.toList()
                     else embAll.keys.filter { labels[it]?.let { l -> l !in exclude } == true }
        if (exclude.isNotEmpty()) println("filter: excluding [${exclude.joinToString(",")}] → ${allIds.size} labelled clips remain")
        val ids: List<String> = if (allIds.size <= maxN) allIds else {
            val lab = allIds.filter { labels.containsKey(it) }
            val rest = allIds.filterNot { labels.containsKey(it) }.sortedBy { it.hashCode() }
            lab + rest.take((maxN - lab.size).coerceAtLeast(0))
        }
        if (ids.size < allIds.size)
            println("subsampled ${ids.size} of ${allIds.size} clips for clustering (all ${ids.count { labels.containsKey(it) }} labelled kept)")

        // z-normalise over the sample, build Z, then drop the full embedding map so the unsampled
        // vectors are GC'd before the (heap-capped) k-means runs.
        val mean = DoubleArray(d); for (id in ids) { val v = embAll[id]!!; for (i in 0 until d) mean[i] += v[i] }
        for (i in 0 until d) mean[i] /= ids.size
        val std = DoubleArray(d); for (id in ids) { val v = embAll[id]!!; for (i in 0 until d) { val e = v[i] - mean[i]; std[i] += e * e } }
        for (i in 0 until d) std[i] = sqrt(std[i] / ids.size).coerceAtLeast(1e-9)
        val Z = ids.map { id -> val v = embAll[id]!!; DoubleArray(d) { (v[it] - mean[it]) / std[it] } }
        embAll.clear()   // free the unsampled vectors; Z now holds only the sample

        // labelled subset (for scoring only)
        val labIdx = ids.indices.filter { labels.containsKey(ids[it]) }
        val yLab = labIdx.map { labels[ids[it]]!! }
        val labelSet = yLab.distinct().sorted()
        println("labelled subset for scoring: ${labIdx.size} clips across ${labelSet.size} labels")

        val rep = StringBuilder()
        rep.append("Unsupervised clustering — corpus=$corpus\n")
        rep.append("clips=${ids.size}  labelled=${labIdx.size}  dims=$d  ks=${KS.joinToString(",")}\n")
        rep.append("labels: ${labelSet.joinToString(", ")}\n\n")
        rep.append(String.format("%-6s %-9s %-8s %-8s%n", "k", "purity", "NMI", "ARI"))

        var bestK = KS.first(); var bestNmi = -1.0; var bestAssign = IntArray(0)
        for (k in KS) {
            val assign = kmeans(Z, k, restarts = 3)
            val cl = labIdx.map { assign[it] }
            val pur = purity(cl, yLab); val nmiV = nmi(cl, yLab); val ariV = ari(cl, yLab)
            val line = String.format("%-6d %-9s %-8s %-8s", k, pct(pur), fmt(nmiV), fmt(ariV))
            println(line); rep.append(line).append("\n")
            if (nmiV > bestNmi) { bestNmi = nmiV; bestK = k; bestAssign = assign }
        }

        // contingency for the best-NMI k
        rep.append("\nBest k=$bestK (NMI=${fmt(bestNmi)}). Cluster × label counts (rows=cluster, dominant label ►):\n\n")
        rep.append(contingency(labIdx.map { bestAssign[it] }, yLab, labelSet))

        // PCA-2D scatter coloured by cluster
        val (p1, p2) = topTwoPCs(Z, d)
        val outTag = corpus + if (exclude.isEmpty()) "" else "_coughonly"
        renderScatter(outTag, Z, bestAssign, bestK, labIdx, yLab, labelSet, p1, p2,
            File(Workspace.dir("codebooks"), "cluster_${outTag}.png"))

        val txt = File(Workspace.dir("codebooks"), "cluster_${outTag}.txt").apply { writeText(rep.toString()) }
        println("\nwrote $txt and cluster_${outTag}.png")
    }

    // ---- binary cough / not-cough discriminator (whole-clip HuBERT → class-balanced logistic reg) ----

    private val COUGH = setOf("dry", "dry hacking", "typical bronchitis", "bronchitis", "croup",
        "wet cough", "cough x2 type unknown", "cough")
    private val NOT_COUGH = setOf("voice", "noise", "snoring", "sneeze")

    /** Segment-level cough/not-cough: chop each recording into short windows (so a long coswara clip
     *  isn't diluted, and HuBERT never sees a huge O(T²) input), energy-gate out silence, embed each
     *  window, label by filename/metadata (AutoLabel), then class-balanced logistic-regression 5-fold CV.
     *  Strictly bounded (seg.max recordings, seg.maxwin windows each) so it can't run away. */
    private fun evalSegmentBinary(corpus: String, wavs: Map<String, File>, labels: Map<String, String>) {
        // Label from the filename recording-type (source__id__RECTYPE__…) — AutoLabel deliberately omits
        // coughs, but coswara cough-heavy/-shallow and esc50 coughing carry it in rec. Manual wins.
        fun grp(id: String): Int? {
            val rec = id.split("__").getOrNull(2)?.lowercase() ?: ""
            val lbl = labels[id]
            return when {
                lbl != null && lbl in COUGH -> 1
                lbl != null && lbl in NOT_COUGH -> 0
                "cough" in rec -> 1                                          // coswara cough-*, esc50 coughing
                "breath" in rec || "vowel" in rec || "counting" in rec -> 0  // respiratory/speech negatives
                else -> AutoLabel.forId(id)?.let { clean(it) }?.let { if (it in COUGH) 1 else if (it in NOT_COUGH) 0 else null }
            }
        }

        val perClass = (System.getProperty("seg.max")?.toIntOrNull() ?: 2000) / 2
        val win = System.getProperty("seg.win")?.toIntOrNull() ?: 1000
        val hop = System.getProperty("seg.hop")?.toIntOrNull() ?: 500
        val maxW = System.getProperty("seg.maxwin")?.toIntOrNull() ?: 6
        val labeled = wavs.keys.mapNotNull { id -> grp(id)?.let { id to it } }
        val cough = labeled.filter { it.second == 1 }.sortedBy { it.first.hashCode() }.take(perClass)
        val notc = labeled.filter { it.second == 0 }.sortedBy { it.first.hashCode() }.take(perClass)
        val sample = cough + notc
        println("=== SEGMENT-level cough/not-cough (${win}ms windows, ${hop}ms hop, ≤$maxW/clip) ===")
        println("sampled ${sample.size} recordings (${cough.size} cough, ${notc.size} not-cough)")

        val dir = Workspace.dir("codebooks")
        val cacheFile = File(dir, "segemb_${corpus}.bin")
        val cache = loadCache(cacheFile)
        val segX = ArrayList<DoubleArray>(); val segY = ArrayList<Int>()
        val newEntries = ArrayList<Pair<String, DoubleArray>>()
        var hubertReady: Boolean? = null
        var doneRec = 0
        for ((id, y) in sample) {
            doneRec++
            if (doneRec % 100 == 0) println("  processed $doneRec / ${sample.size} recordings, ${segX.size} segments")
            // gather this clip's window keys; use cache if all present
            val wav = wavs[id] ?: continue
            var pcm: FloatArray? = null
            for (w in 0 until maxW) {
                val key = "$id#$w"
                val cached = cache[key]
                if (cached != null) { segX.add(cached); segY.add(y); continue }
                // need to compute window w — decode once
                if (pcm == null) {
                    pcm = AudioDecoder.decode(wav)?.also { rms(it) } ?: break
                    if (pcm.size < SR / 16) break
                }
                val sMs = w * hop; val startS = (sMs / 1000.0 * SR).toInt()
                if (startS >= pcm.size) break
                val endS = ((sMs + win) / 1000.0 * SR).toInt().coerceAtMost(pcm.size)
                if (endS - startS < SR / 16) break
                val chunk = pcm.copyOfRange(startS, endS)
                var e = 0.0; for (x in chunk) e += x.toDouble() * x
                if (sqrt(e / chunk.size) < 0.03) continue   // silence gate (clip is RMS-normalised to 0.1)
                if (hubertReady == null) {
                    hubertReady = HubertKMeansUnits.available
                    println(if (hubertReady == true) "HuBERT ${HubertKMeansUnits.provider}" else "HuBERT unavailable — need a GPU run")
                }
                if (hubertReady != true) return
                val fe = HubertKMeansUnits.frameEmbeddings(chunk, SR) ?: continue
                if (fe.isEmpty()) continue
                val h = fe[0].size; val v = DoubleArray(h)
                for (f in fe) for (j in 0 until h) v[j] += f[j]
                for (j in 0 until h) v[j] /= fe.size
                segX.add(v); segY.add(y); newEntries.add(key to v)
            }
        }
        if (newEntries.isNotEmpty()) {   // append new segments to cache
            DataOutputStream(java.io.FileOutputStream(cacheFile, true).buffered()).use { dos ->
                for ((k, v) in newEntries) writeEntry(dos, k, v)
            }
        }
        val n = segX.size; val nC = segY.count { it == 1 }
        if (n < 20) { println("segment-binary: too few segments ($n)"); return }
        val d = segX[0].size
        // z-normalise
        val mean = DoubleArray(d); for (v in segX) for (i in 0 until d) mean[i] += v[i]
        for (i in 0 until d) mean[i] /= n
        val std = DoubleArray(d); for (v in segX) for (i in 0 until d) { val e = v[i] - mean[i]; std[i] += e * e }
        for (i in 0 until d) std[i] = sqrt(std[i] / n).coerceAtLeast(1e-9)
        val X = segX.map { v -> DoubleArray(d) { (v[it] - mean[it]) / std[it] } }
        println("$n segments ($nC cough, ${n - nC} not-cough)")
        var tp = 0; var fp = 0; var tn = 0; var fn = 0
        for (fold in 0 until 5) {
            val test = X.indices.filter { it % 5 == fold }; val train = X.indices.filter { it % 5 != fold }
            if (train.isEmpty() || test.isEmpty()) continue
            val (w, b) = SoftmaxLR.train(train.map { X[it] }, train.map { segY[it] }, 2)
            for (i in test) {
                val pred = if (SoftmaxLR.probs(w, b, X[i])[1] >= 0.5) 1 else 0
                when { pred == 1 && segY[i] == 1 -> tp++; pred == 1 && segY[i] == 0 -> fp++; pred == 0 && segY[i] == 0 -> tn++; else -> fn++ }
            }
        }
        val acc = (tp + tn).toDouble() / n
        val prec = tp.toDouble() / (tp + fp).coerceAtLeast(1); val rec = tp.toDouble() / (tp + fn).coerceAtLeast(1)
        println("5-fold CV (segment-level): accuracy ${pct(acc)}  |  cough precision ${pct(prec)}  recall ${pct(rec)}  F1 ${fmt(2 * prec * rec / (prec + rec).coerceAtLeast(1e-9))}")
        println("confusion: TP=$tp FN=$fn FP=$fp TN=$tn")
    }

    private fun evalBinary(emb: Map<String, DoubleArray>, labels: Map<String, String>) {
        val items = emb.keys.filter { labels.containsKey(it) }.map { it to (labels[it] in COUGH) }
        if (items.size < 20) { println("binary: too few labelled clips (${items.size})"); return }
        val d = emb.values.first().size; val n = items.size
        // z-normalise over the labelled clips
        val mean = DoubleArray(d); for ((id, _) in items) { val v = emb[id]!!; for (i in 0 until d) mean[i] += v[i] }
        for (i in 0 until d) mean[i] /= n
        val std = DoubleArray(d); for ((id, _) in items) { val v = emb[id]!!; for (i in 0 until d) { val e = v[i] - mean[i]; std[i] += e * e } }
        for (i in 0 until d) std[i] = sqrt(std[i] / n).coerceAtLeast(1e-9)
        val X = items.map { (id, _) -> val v = emb[id]!!; DoubleArray(d) { (v[it] - mean[it]) / std[it] } }
        val Y = items.map { if (it.second) 1 else 0 }
        val nC = Y.count { it == 1 }
        println("\n=== BINARY cough / not-cough discriminator (HuBERT whole-clip) ===")
        println("$n labelled clips: $nC cough, ${n - nC} not-cough")

        var tp = 0; var fp = 0; var tn = 0; var fn = 0
        for (fold in 0 until 5) {
            val test = X.indices.filter { it % 5 == fold }; val train = X.indices.filter { it % 5 != fold }
            if (train.isEmpty() || test.isEmpty()) continue
            val (w, b) = SoftmaxLR.train(train.map { X[it] }, train.map { Y[it] }, 2)
            for (i in test) {
                val pred = if (SoftmaxLR.probs(w, b, X[i])[1] >= 0.5) 1 else 0
                when { pred == 1 && Y[i] == 1 -> tp++; pred == 1 && Y[i] == 0 -> fp++; pred == 0 && Y[i] == 0 -> tn++; else -> fn++ }
            }
        }
        val acc = (tp + tn).toDouble() / n
        val prec = tp.toDouble() / (tp + fp).coerceAtLeast(1)
        val rec = tp.toDouble() / (tp + fn).coerceAtLeast(1)
        val f1 = 2 * prec * rec / (prec + rec).coerceAtLeast(1e-9)
        println("5-fold CV: accuracy ${pct(acc)}  |  cough precision ${pct(prec)}  recall ${pct(rec)}  F1 ${fmt(f1)}")
        println("confusion: TP=$tp (cough→cough)  FN=$fn (cough missed)  FP=$fp (false alarm)  TN=$tn (bg rejected)")
    }

    // ---- embeddings + append-cache -----------------------------------------------------------------

    private fun computeEmbeddings(corpus: String, wavs: Map<String, File>): LinkedHashMap<String, DoubleArray> {
        val dir = Workspace.dir("codebooks")
        val cacheFile = File(dir, "clipemb_${corpus}.bin")
        val skipFile = File(dir, "clipemb_${corpus}.skip")   // ids that decode-fail / are too short — never retried
        val cache = loadCache(cacheFile)
        val skip = if (skipFile.isFile) skipFile.readLines().mapNotNull { it.trim().ifEmpty { null } }.toHashSet() else HashSet()
        val out = LinkedHashMap<String, DoubleArray>()
        for ((id, _) in wavs) cache[id]?.let { out[id] = it }
        val todo = wavs.filterKeys { it !in out && it !in skip }
        println("embedding cache: ${out.size} present, ${skip.size} known-bad, ${todo.size} to compute")

        if (todo.isNotEmpty()) {
            var embedded = 0; val newSkip = ArrayList<String>()
            var hubertReady: Boolean? = null   // the 377 MB model loads ONLY when a real embeddable clip appears
            for ((id, wav) in todo) {
                val pcm = AudioDecoder.decode(wav)?.also { rms(it) }
                // Skip decode-fails, <62 ms clips, AND >30 s clips: HuBERT self-attention is O(T²), so a
                // long/corrupt clip demands a hundreds-of-GB attention buffer and OOMs the whole machine.
                if (pcm == null || pcm.size < SR / 16 || pcm.size > SR * 30) { newSkip.add(id); continue }
                if (hubertReady == null) {
                    hubertReady = HubertKMeansUnits.available
                    println(if (hubertReady == true) "HuBERT ${HubertKMeansUnits.provider} — embedding new clips…"
                            else "HuBERT unavailable (${HubertKMeansUnits.unavailableReason}) — new clips left for a GPU run")
                }
                if (hubertReady != true) break
                val fe = HubertKMeansUnits.frameEmbeddings(pcm, SR)
                if (fe == null || fe.isEmpty()) { newSkip.add(id); continue }
                val h = fe[0].size; val v = DoubleArray(h)
                for (f in fe) for (j in 0 until h) v[j] += f[j]
                for (j in 0 until h) v[j] /= fe.size
                out[id] = v; embedded++
                if (embedded % 200 == 0) println("  embedded $embedded")
            }
            if (embedded > 0) {   // rewrite the cache only if something new was actually embedded
                val tmp = File(dir, "clipemb_${corpus}.bin.tmp")
                DataOutputStream(tmp.outputStream().buffered()).use { dos -> for ((id, v) in out) writeEntry(dos, id, v) }
                tmp.copyTo(cacheFile, overwrite = true); tmp.delete()
            }
            if (newSkip.isNotEmpty()) skipFile.appendText(newSkip.joinToString("\n", postfix = "\n"))
        }
        println("embeddings ready: ${out.size}")
        return out
    }

    private fun writeEntry(dos: DataOutputStream, id: String, v: DoubleArray) {
        dos.writeUTF(id); dos.writeInt(v.size); for (x in v) dos.writeFloat(x.toFloat())
    }

    private fun loadCache(f: File): HashMap<String, DoubleArray> {
        val m = HashMap<String, DoubleArray>()
        if (!f.isFile) return m
        try {
            DataInputStream(f.inputStream().buffered()).use { dis ->
                while (true) {
                    val id = try { dis.readUTF() } catch (e: EOFException) { break }
                    val n = dis.readInt(); val v = DoubleArray(n) { dis.readFloat().toDouble() }
                    m[id] = v
                }
            }
        } catch (e: Exception) { System.err.println("cache read (${f.name}): ${e.message}") }
        return m
    }

    // ---- k-means (k-means++ init, deterministic, restarts, pick lowest inertia) ---------------------

    private fun kmeans(data: List<DoubleArray>, k: Int, restarts: Int = 5): IntArray {
        val n = data.size; val dim = data[0].size
        var bestAssign = IntArray(n); var bestInertia = Double.MAX_VALUE
        for (r in 0 until restarts) {
            val rnd = Random((1000L + r * 7919L))
            val cent = kmeansPlusPlus(data, k, rnd)
            val assign = IntArray(n)
            repeat(60) {
                var moved = false
                for (i in 0 until n) {
                    var best = 0; var bd = Double.MAX_VALUE
                    for (c in 0 until k) { val dd = dist2(data[i], cent[c]); if (dd < bd) { bd = dd; best = c } }
                    if (assign[i] != best) { assign[i] = best; moved = true }
                }
                val sum = Array(k) { DoubleArray(dim) }; val cnt = IntArray(k)
                for (i in 0 until n) { val c = assign[i]; cnt[c]++; val di = data[i]; val s = sum[c]; for (j in 0 until dim) s[j] += di[j] }
                for (c in 0 until k) if (cnt[c] > 0) for (j in 0 until dim) cent[c][j] = sum[c][j] / cnt[c]
                if (!moved) return@repeat
            }
            var inertia = 0.0; for (i in 0 until n) inertia += dist2(data[i], cent[assign[i]])
            if (inertia < bestInertia) { bestInertia = inertia; bestAssign = assign.copyOf() }
        }
        return bestAssign
    }

    private fun kmeansPlusPlus(data: List<DoubleArray>, k: Int, rnd: Random): Array<DoubleArray> {
        val n = data.size
        val cent = ArrayList<DoubleArray>(k)
        cent.add(data[rnd.nextInt(n)].copyOf())
        val d2 = DoubleArray(n) { dist2(data[it], cent[0]) }
        while (cent.size < k) {
            val total = d2.sum().coerceAtLeast(1e-12)
            var target = rnd.nextDouble() * total; var idx = 0
            while (idx < n - 1) { target -= d2[idx]; if (target <= 0) break; idx++ }
            cent.add(data[idx].copyOf())
            for (i in 0 until n) { val nd = dist2(data[i], cent.last()); if (nd < d2[i]) d2[i] = nd }
        }
        return cent.toTypedArray()
    }

    private fun dist2(a: DoubleArray, b: DoubleArray): Double { var s = 0.0; for (i in a.indices) { val d = a[i] - b[i]; s += d * d }; return s }

    // ---- clustering-vs-label metrics ---------------------------------------------------------------

    private fun table(cl: List<Int>, lab: List<String>): Pair<Array<IntArray>, List<String>> {
        val labs = lab.distinct().sorted(); val li = labs.withIndex().associate { (i, s) -> s to i }
        val kc = (cl.maxOrNull() ?: -1) + 1
        val t = Array(kc) { IntArray(labs.size) }
        for (i in cl.indices) t[cl[i]][li[lab[i]]!!]++
        return t to labs
    }

    private fun purity(cl: List<Int>, lab: List<String>): Double {
        val (t, _) = table(cl, lab); val n = cl.size
        return t.sumOf { (it.maxOrNull() ?: 0) }.toDouble() / n.coerceAtLeast(1)
    }

    private fun nmi(cl: List<Int>, lab: List<String>): Double {
        val (t, _) = table(cl, lab); val n = cl.size.toDouble()
        val a = t.map { it.sum() }; val b = IntArray(t[0].size) { j -> t.sumOf { it[j] } }
        var i = 0.0
        for (r in t.indices) for (c in t[r].indices) { val nij = t[r][c]; if (nij > 0) i += nij / n * ln(nij * n / (a[r].toDouble() * b[c])) }
        val hc = -a.sumOf { if (it > 0) it / n * ln(it / n) else 0.0 }
        val hl = -b.sumOf { if (it > 0) it / n * ln(it / n) else 0.0 }
        val den = (hc + hl) / 2
        return if (den > 1e-12) i / den else 0.0
    }

    private fun ari(cl: List<Int>, lab: List<String>): Double {
        val (t, _) = table(cl, lab); val n = cl.size.toLong()
        fun c2(x: Int) = x.toLong() * (x - 1) / 2
        val sumIJ = t.sumOf { row -> row.sumOf { c2(it) } }
        val ai = t.sumOf { c2(it.sum()) }
        val bj = (0 until t[0].size).sumOf { j -> c2(t.sumOf { it[j] }) }
        val nn = c2(n.toInt().coerceAtLeast(0)).toDouble().let { if (n > Int.MAX_VALUE) n.toDouble() * (n - 1) / 2 else it }
        val expected = ai.toDouble() * bj / nn
        val max = (ai + bj) / 2.0
        return if (max - expected != 0.0) (sumIJ - expected) / (max - expected) else 0.0
    }

    private fun contingency(cl: List<Int>, lab: List<String>, labelSet: List<String>): String {
        val (t, labs) = table(cl, lab)
        val sb = StringBuilder()
        val short = labelSet.map { it.take(10) }
        sb.append(String.format("%-8s", "cluster")); short.forEach { sb.append(String.format("%-11s", it)) }
        sb.append("  n   ► dominant\n")
        for (r in t.indices) {
            val row = t[r]; val rn = row.sum(); if (rn == 0) continue
            sb.append(String.format("%-8d", r))
            for (j in labs.indices) sb.append(String.format("%-11d", row[j]))
            val dom = labs[row.indices.maxByOrNull { row[it] } ?: 0]
            sb.append(String.format("  %-4d► %s (%.0f%%)\n", rn, dom, 100.0 * (row.maxOrNull() ?: 0) / rn))
        }
        return sb.toString()
    }

    // ---- PCA-2D + render ---------------------------------------------------------------------------

    private fun renderScatter(corpus: String, Z: List<DoubleArray>, assign: IntArray, k: Int,
                              labIdx: List<Int>, yLab: List<String>, labelSet: List<String>,
                              pc1: DoubleArray, pc2: DoubleArray, out: File) {
        val proj = Z.map { v -> var a = 0.0; var b = 0.0; for (i in v.indices) { a += v[i] * pc1[i]; b += v[i] * pc2[i] }; a to b }
        val xs = proj.map { it.first }; val ys = proj.map { it.second }
        // dominant label per cluster (for the legend), from the labelled subset
        val perCluster = HashMap<Int, HashMap<String, Int>>()
        for ((n, gi) in labIdx.withIndex()) perCluster.getOrPut(assign[gi]) { HashMap() }.merge(yLab[n], 1, Int::plus)
        val domOf = (0 until k).associateWith { c -> perCluster[c]?.maxByOrNull { it.value }?.key ?: "—" }
        val col = (0 until k).associateWith { c -> Color.getHSBColor(c.toFloat() / k, 0.72f, 0.95f) }

        val W = 1200; val H = 900
        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB); val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x1e, 0x1e, 0x22); g.fillRect(0, 0, W, H)
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        fun sx(x: Double) = (300 + (x - minX) / (maxX - minX + 1e-9) * (W - 340)).toInt()
        fun sy(y: Double) = (H - 40 - (y - minY) / (maxY - minY + 1e-9) * (H - 80)).toInt()
        for (i in Z.indices) { g.color = col[assign[i]]; val x = sx(proj[i].first); val y = sy(proj[i].second); g.fillOval(x - 3, y - 3, 6, 6) }
        // legend: cluster colour → its dominant label + share
        g.font = Font("SansSerif", Font.BOLD, 15); g.color = Color(0xdd, 0xdd, 0xdd)
        g.drawString("$corpus — $k clusters (PCA-2D, coloured by discovered cluster)", 16, 24)
        g.font = Font("SansSerif", Font.PLAIN, 13)
        var ly = 54
        for (c in 0 until k) {
            g.color = col[c]!!; g.fillOval(16, ly - 10, 12, 12)
            g.color = Color(0xcc, 0xcc, 0xcc)
            g.drawString("c$c ► ${domOf[c]}", 34, ly); ly += 22
        }
        g.dispose(); ImageIO.write(img, "png", out)
    }

    private fun topTwoPCs(z: List<DoubleArray>, d: Int): Pair<DoubleArray, DoubleArray> {
        val cov = Array(d) { DoubleArray(d) }
        for (v in z) for (i in 0 until d) { val vi = v[i]; for (j in i until d) cov[i][j] += vi * v[j] }
        for (i in 0 until d) for (j in i until d) { cov[i][j] /= z.size; cov[j][i] = cov[i][j] }
        val pc1 = powerIter(cov, d)
        var lam = 0.0; for (i in 0 until d) { var t = 0.0; for (j in 0 until d) t += cov[i][j] * pc1[j]; lam += pc1[i] * t }
        val cov2 = Array(d) { i -> DoubleArray(d) { j -> cov[i][j] - lam * pc1[i] * pc1[j] } }
        return pc1 to powerIter(cov2, d)
    }

    private fun powerIter(cov: Array<DoubleArray>, d: Int): DoubleArray {
        var v = DoubleArray(d) { if (it == 0) 1.0 else 0.0 }
        repeat(150) {
            val nv = DoubleArray(d); for (i in 0 until d) { var s = 0.0; for (j in 0 until d) s += cov[i][j] * v[j]; nv[i] = s }
            var n = 0.0; for (x in nv) n += x * x; n = sqrt(n).coerceAtLeast(1e-12); for (i in 0 until d) nv[i] /= n; v = nv
        }
        return v
    }

    // ---- labels (mirror the codebook build's canonicalisation) -------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun loadLabels(): Map<String, String> {
        val f = Workspace.file("manual_comments.json")
        val manual = if (f.isFile) runCatching { Gson().fromJson(f.readText(), Map::class.java) as Map<String, String> }.getOrNull() ?: emptyMap() else emptyMap()
        val out = HashMap<String, String>()
        // manual comments (cleaned + date-split), plus any auto-labelable id
        val ids = manual.keys
        for (id in ids) { val base = clean(manual[id]) ?: continue; bronchitis(id, base)?.let { out[id] = it } }
        return out
    }

    private fun clean(raw: String?): String? {
        var s = raw?.trim() ?: return null
        val am = s.indexOf("auto-match", ignoreCase = true); if (am >= 0) s = s.substring(0, am)
        s = s.trim().removePrefix("manual:").trim().lowercase()
        if (s.isBlank()) return null
        return when {
            s == "snore" || s.contains("snor") -> "snoring"
            s.contains("bronchitis") || s.contains("brinchitis") || s.contains("evin") || s.contains("quad") -> "bronchitis"
            s.startsWith("dry hack") -> "dry hacking"
            s.startsWith("dry") -> "dry"
            s == "noise" -> "noise"
            s == "croup" -> "croup"
            s == "speech" || s.contains("music") || s.contains("singing") || s == "crying" || s == "cry" -> "voice"
            s == "sneeze" || s == "sneezing" -> "sneeze"
            else -> s
        }
    }

    private val dateRe = Regex("(20\\d{6})")
    private fun bronchitis(id: String, label: String?): String? {
        if (label != "bronchitis") return label
        val date = dateRe.find(id)?.groupValues?.get(1)?.toIntOrNull() ?: return label
        return when {
            date in 20260601..20260612 -> "typical bronchitis"
            date in 20260613..20260615 -> null
            date >= 20260616 -> "dry hacking"
            else -> label
        }
    }

    private fun rms(pcm: FloatArray, target: Float = 0.1f) {
        var s = 0.0; for (x in pcm) s += x.toDouble() * x
        val r = sqrt(s / pcm.size.coerceAtLeast(1)); if (r > 1e-5) { val g = (target / r).toFloat(); for (i in pcm.indices) pcm[i] *= g }
    }

    private fun fmt(x: Double) = String.format("%.3f", x)
    private fun pct(x: Double) = String.format("%.0f%%", x * 100)
}
