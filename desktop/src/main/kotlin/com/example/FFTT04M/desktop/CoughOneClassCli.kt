package com.example.FFTT04M.desktop

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import kotlin.math.sqrt

/**
 * Concept test for the ONE-CLASS cough library (COUGH_ISOLATION.md): build a cough-ONLY codebook (k-means
 * over confirmed-cough clip embeddings, subtype-naive) and score every clip by how well it FITS the cough
 * manifold (distance to nearest cough unit). Low distance = "coughy". Tests the hypothesis that a
 * cough-only fit-score rejects non-coughs — and, crucially, how rejection varies by negative type (the
 * prediction: FAR negatives — speech/noise/music — are rejected hard; BREATH, which overlaps cough
 * acoustically, is barely rejected). Uses cached whole-clip HuBERT (clipemb_ALLDATA.bin); no GPU.
 *
 * Cough recall is held-out (codebook built on a train split of cough clips). Operating point = the
 * distance threshold giving 90% recall on held-out coughs; report each negative group's FP at it.
 * Run: ./gradlew :desktop:coughOneClass -Deval.alldata=D:\AndroidProjects\ALLDATA
 */
object CoughOneClassCli {

    private const val K = 64
    private const val TRAIN_CAP = 8000

    private fun loadEmb(f: File): HashMap<String, DoubleArray> {
        val m = HashMap<String, DoubleArray>(90_000)
        if (!f.isFile) return m
        DataInputStream(f.inputStream().buffered()).use { dis ->
            while (true) {
                val id = try { dis.readUTF() } catch (e: EOFException) { break }
                val n = dis.readInt(); val v = DoubleArray(n) { dis.readFloat().toDouble() }
                var s = 0.0; for (x in v) s += x * x; val nrm = sqrt(s).coerceAtLeast(1e-9)   // L2 → cosine geometry
                for (i in v.indices) v[i] /= nrm
                m[id] = v
            }
        }
        return m
    }

    private fun dist2(a: DoubleArray, b: DoubleArray): Double { var s = 0.0; for (i in a.indices) { val d = a[i] - b[i]; s += d * d }; return s }
    private fun nearest(x: DoubleArray, c: List<DoubleArray>): Double { var m = Double.MAX_VALUE; for (k in c) { val d = dist2(x, k); if (d < m) m = d }; return m }

    @JvmStatic
    fun main(args: Array<String>) {
        val repo = Workspace.repoRoot ?: File(".")
        val alldata = File(System.getProperty("eval.alldata")?.takeIf { it.isNotBlank() } ?: File(repo, "ALLDATA").path)
        val emb = loadEmb(File(Workspace.dir("codebooks"), "clipemb_ALLDATA.bin"))
        if (emb.isEmpty()) { println("no clipemb_ALLDATA.bin"); return }
        val meta = File(alldata, "metadata.csv"); if (!meta.isFile) { println("missing $meta"); return }

        // id -> (source, soundType, isCough)
        data class M(val source: String, val st: String, val cough: Boolean)
        val info = HashMap<String, M>(90_000)
        meta.useLines { seq -> seq.drop(1).forEach { line ->
            val c = line.split(','); if (c.size < 5) return@forEach
            info[c[0].trim().removeSuffix(".wav")] = M(c[1].trim(), c[3].trim(), CoughTruth.parseIsCough(c[4]) == true)
        } }

        fun fold(id: String) = ((id.hashCode() % 5) + 5) % 5
        val coughTrain = ArrayList<DoubleArray>(); val coughTest = ArrayList<DoubleArray>()
        for ((id, e) in emb) { val m = info[id] ?: continue; if (m.cough) { if (fold(id) == 0) coughTest.add(e) else coughTrain.add(e) } }
        println("=== ONE-CLASS COUGH LIBRARY (HuBERT ${emb.values.first().size}-d, K=$K) ===")
        println("cough train ${coughTrain.size} · cough test ${coughTest.size}")
        if (coughTrain.size < 500 || coughTest.size < 100) { println("insufficient cough data"); return }

        // subsample train (deterministic stride) then k-means (deterministic init by stride)
        val train = if (coughTrain.size > TRAIN_CAP) (0 until TRAIN_CAP).map { coughTrain[it * coughTrain.size / TRAIN_CAP] } else coughTrain
        var cent = (0 until K).map { train[it * train.size / K].copyOf() }
        repeat(12) {
            val sum = Array(K) { DoubleArray(train[0].size) }; val cnt = IntArray(K)
            for (x in train) { var bi = 0; var bd = Double.MAX_VALUE; for (k in 0 until K) { val d = dist2(x, cent[k]); if (d < bd) { bd = d; bi = k } }
                cnt[bi]++; val s = sum[bi]; for (j in x.indices) s[j] += x[j] }
            cent = (0 until K).map { k -> if (cnt[k] > 0) DoubleArray(train[0].size) { sum[k][it] / cnt[k] } else cent[k] }
        }

        // operating point: distance threshold for 90% recall on held-out coughs
        val testD = coughTest.map { nearest(it, cent) }.sorted()
        val thr = testD[(0.90 * (testD.size - 1)).toInt()]
        val recall = coughTest.count { nearest(it, cent) <= thr }.toDouble() / coughTest.size
        println("threshold @ %.1f%% held-out cough recall = %.4f\n".format(recall * 100, thr))

        // per-group FP (fraction of NON-cough clips that fit the cough manifold, i.e. dist <= thr)
        data class G(var n: Int = 0, var fp: Int = 0)
        val groups = LinkedHashMap<String, G>()
        for ((id, e) in emb) {
            val m = info[id] ?: continue; if (m.cough) continue
            val key = if (m.source.equals("Coswara", true)) "Coswara/${m.st}" else m.source
            val g = groups.getOrPut(key) { G() }; g.n++; if (nearest(e, cent) <= thr) g.fp++
        }
        println("-- one-class rejection by negative group (FP = fits cough manifold) --")
        groups.toList().sortedByDescending { it.second.fp.toDouble() / it.second.n }.forEach { (k, g) ->
            println("  %-30s FP %5.1f%%  reject %5.1f%%  (n=%d)".format(k, 100.0 * g.fp / g.n, 100.0 * (g.n - g.fp) / g.n, g.n))
        }
    }
}
