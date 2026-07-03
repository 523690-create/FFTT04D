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
        if (!HubertKMeansUnits.available) { println("HuBERT unavailable: ${HubertKMeansUnits.unavailableReason}"); return }
        val corpus = args.getOrNull(0)?.takeIf { it.isNotBlank() }
            ?: System.getProperty("cluster.corpus")?.takeIf { it.isNotBlank() } ?: "p3"
        val repo = Workspace.repoRoot ?: File(".")
        val corpusDir = File(repo, corpus)
        if (!corpusDir.isDirectory) { println("no corpus dir: $corpusDir"); return }
        println("=== Unsupervised clustering: $corpus (whole-clip HuBERT) ===  provider=${HubertKMeansUnits.provider}")

        val wavs = corpusDir.walkTopDown().filter { it.isFile && it.extension.equals("wav", true) }
            .associateBy { it.nameWithoutExtension }
        println("clips: ${wavs.size}")
        // Label each clip for SCORING ONLY: manual comment (cleaned + bronchitis date-split) wins, else
        // the filename auto-label (coswara/urban8k/train/esc50 → voice/noise/…). Clips with neither are
        // clustered but excluded from the label-agreement scores.
        val manual = loadLabels()
        val labels = HashMap<String, String>()
        for (id in wavs.keys) (manual[id] ?: AutoLabel.forId(id)?.let { clean(it) })?.let { labels[id] = it }

        // ---- embeddings (cached on disk; resumable append) ----
        val embAll = computeEmbeddings(corpus, wavs)
        if (embAll.size < KS.max()) { println("too few embeddings (${embAll.size})"); return }
        val d = embAll.values.first().size

        // Subsample for clustering to BOUND MEMORY — the full 76k×768 matrix in an uncapped heap once
        // exhausted RAM and took the machine down. Keep ALL labelled clips + a deterministic random fill
        // up to MAX_N (a 30k sample is ample for discovering cluster structure).
        val maxN = System.getProperty("cluster.max")?.toIntOrNull() ?: 30000
        val allIds = embAll.keys.toList()
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
        renderScatter(corpus, Z, bestAssign, bestK, labIdx, yLab, labelSet, p1, p2,
            File(Workspace.dir("codebooks"), "cluster_${corpus}.png"))

        val txt = File(Workspace.dir("codebooks"), "cluster_${corpus}.txt").apply { writeText(rep.toString()) }
        println("\nwrote $txt and cluster_${corpus}.png")
    }

    // ---- embeddings + append-cache -----------------------------------------------------------------

    private fun computeEmbeddings(corpus: String, wavs: Map<String, File>): LinkedHashMap<String, DoubleArray> {
        val cacheFile = File(Workspace.dir("codebooks"), "clipemb_${corpus}.bin")
        val cache = loadCache(cacheFile)
        println("embedding cache: ${cache.size} present, ${wavs.size - cache.count { it.key in wavs }} new to compute")
        val out = LinkedHashMap<String, DoubleArray>()
        // keep cached embeddings that still belong to this corpus
        for ((id, wav) in wavs) cache[id]?.let { out[id] = it }
        val todo = wavs.filterKeys { it !in out }
        if (todo.isNotEmpty()) {
            // Write existing (kept) + newly computed to a .tmp, then atomically replace the cache — so a
            // crash mid-run never corrupts the real cache file.
            val tmp = File(Workspace.dir("codebooks"), "clipemb_${corpus}.bin.tmp")
            val dos = DataOutputStream(tmp.outputStream().buffered())
            for ((id, v) in out) writeEntry(dos, id, v)
            var done = 0
            for ((id, wav) in todo) {
                val pcm = AudioDecoder.decode(wav)?.also { rms(it) } ?: continue
                if (pcm.size < SR / 16) continue   // ~62ms floor — too short crashes HuBERT's conv stack (Conv on {0})
                val fe = HubertKMeansUnits.frameEmbeddings(pcm, SR) ?: continue
                if (fe.isEmpty()) continue
                val h = fe[0].size; val v = DoubleArray(h)
                for (f in fe) for (j in 0 until h) v[j] += f[j]
                for (j in 0 until h) v[j] /= fe.size
                out[id] = v; writeEntry(dos, id, v)
                done++
                if (done % 200 == 0) { dos.flush(); println("  embedded $done / ${todo.size}") }
            }
            dos.flush(); dos.close()
            tmp.copyTo(cacheFile, overwrite = true); tmp.delete()
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
