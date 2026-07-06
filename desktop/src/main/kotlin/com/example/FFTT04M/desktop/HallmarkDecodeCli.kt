package com.example.FFTT04M.desktop

import com.example.FFTT04M.desktop.fractionation.HubertKMeansUnits
import com.google.gson.Gson
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * Decode every harvested segment with the exported hallmark codebook (cough_hallmark_units.json): per
 * 180/90ms HuBERT-768 window → nearest of the 256 unit centroids → a segment "hits" if any window lands on
 * a HALLMARK unit. Then FUSE the hallmark hit with the head+wavelet 2-way consensus (from harvest_compare.csv)
 * to shrink the manual-review pile: the hallmark casts the deciding vote on the segments where head and
 * wavelet disagree. Writes harvest_hallmark.csv + harvest_triage.csv.
 *
 * Run (needs cough_hallmark_units.json from coughPhonemes -Dcp.export): ./gradlew :desktop:hallmarkDecode -PuseOnnxGpu
 */
object HallmarkDecodeCli {
    private const val SR = 44100

    @JvmStatic
    fun main(args: Array<String>) {
        val repo = Workspace.repoRoot ?: File(".")
        val harvest = File(System.getProperty("hd.harvest")?.takeIf { it.isNotBlank() } ?: File(repo, "cough_harvest").path)
        val cbFile = File(Workspace.dir("codebooks"), "cough_hallmark_units.json")
        if (!cbFile.isFile) { println("no $cbFile — run coughPhonemes -Dcp.export=true first"); return }
        if (!HubertKMeansUnits.available) { println("HuBERT unavailable: ${HubertKMeansUnits.unavailableReason}"); return }
        @Suppress("UNCHECKED_CAST")
        val cb = Gson().fromJson(cbFile.readText(), Map::class.java) as Map<String, Any?>
        val winMs = (cb["winMs"] as Number).toInt(); val hopMs = (cb["hopMs"] as Number).toInt()
        val norm = cb["norm"] as Map<*, *>
        val mean = (norm["mean"] as List<*>).map { (it as Number).toDouble() }.toDoubleArray()
        val std = (norm["std"] as List<*>).map { (it as Number).toDouble() }.toDoubleArray()
        val unitList = cb["units"] as List<*>
        val K = unitList.size
        val cent = Array(K) { DoubleArray(mean.size) }; val isHall = BooleanArray(K)
        for (u in unitList) {
            val m = u as Map<*, *>; val id = (m["id"] as Number).toInt()
            isHall[id] = m["isHallmark"] == true
            val c = m["centroid"] as List<*>; for (j in c.indices) cent[id][j] = (c[j] as Number).toDouble()
        }
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        println("=== HALLMARK DECODE over $harvest ($K units, ${isHall.count { it }} hallmarks, ${winMs}/${hopMs}ms, HuBERT ${HubertKMeansUnits.provider}) ===")

        val wavs = harvest.walkTopDown().filter { it.isFile && it.extension.equals("wav", true) }.toList()
        val hit = ConcurrentHashMap<String, Boolean>(); val nHall = ConcurrentHashMap<String, Int>(); val bucketOf = ConcurrentHashMap<String, String>()
        val done = AtomicInteger()
        val pool = Executors.newFixedThreadPool(4)
        try {
            wavs.map { wav ->
                pool.submit {
                    try {
                        val pcm = AudioDecoder.decode(wav)?.also { rms(it) }
                        if (pcm != null && pcm.size >= SR / 16 && pcm.size <= SR * 8) {
                            val fe = HubertKMeansUnits.frameEmbeddings(pcm, SR)
                            if (fe != null && fe.isNotEmpty()) {
                                val durMs = (pcm.size.toLong() * 1000 / SR).toInt(); val T = fe.size; val d = fe[0].size
                                var hall = 0
                                for ((sMs, eMs) in framesFor(durMs, winMs, hopMs)) {
                                    val f0 = (sMs.toLong() * T / durMs).toInt().coerceIn(0, T - 1)
                                    val f1 = (eMs.toLong() * T / durMs).toInt().coerceIn(f0 + 1, T)
                                    val v = DoubleArray(d); for (f in f0 until f1) { val fr = fe[f]; for (j in 0 until d) v[j] += fr[j] }
                                    val inv = 1.0 / (f1 - f0); for (j in 0 until d) v[j] = (v[j] * inv - mean[j]) / std[j]
                                    var best = 0; var bd = Double.MAX_VALUE
                                    for (c in 0 until K) { var s = 0.0; val cc = cent[c]; for (j in 0 until d) { val e = v[j] - cc[j]; s += e * e }; if (s < bd) { bd = s; best = c } }
                                    if (isHall[best]) hall++
                                }
                                val id = wav.nameWithoutExtension
                                hit[id] = hall > 0; nHall[id] = hall; bucketOf[id] = bucketName(wav, harvest)
                            }
                        }
                    } catch (_: Exception) {}
                    val n = done.incrementAndGet(); if (n % 10000 == 0 || n == wavs.size) println("  decoded $n/${wavs.size}")
                }
            }.forEach { it.get() }
        } finally { pool.shutdown() }
        File(harvest, "harvest_hallmark.csv").bufferedWriter().use { w ->
            w.write("id,bucket,hallmarkHit,nHallmarkWindows\n")
            for (id in hit.keys.sorted()) w.write("$id,${bucketOf[id]},${hit.getValue(id)},${nHall[id]}\n")
        }
        println("=== wrote harvest_hallmark.csv (${hit.size} decoded) ===")

        // ---- FUSE with head+wavelet 2-way consensus from harvest_compare.csv --------------------------
        val comp = File(harvest, "harvest_compare.csv")
        if (!comp.isFile) { println("no harvest_compare.csv — hallmark scores written, skipping fusion"); return }
        val headC = HashMap<String, Boolean>(); val wavC = HashMap<String, Boolean>()
        comp.useLines { lines -> lines.drop(1).forEach { ln -> val c = ln.split(','); if (c.size >= 7) { wavC[c[0]] = c[5] == "true"; headC[c[0]] = c[6] == "true" } } }
        val ids = hit.keys.filter { headC.containsKey(it) && wavC.containsKey(it) }
        if (ids.isEmpty()) { println("no clips share hallmark + head + wav"); return }

        // 2-way piles + hallmark hit-rate within each
        var accBoth = 0; var accHall = 0; var rejBoth = 0; var rejHall = 0; var dis = 0; var disHall = 0
        var uCough = 0; var uNot = 0
        val out = File(harvest, "harvest_triage.csv").bufferedWriter()
        out.write("id,bucket,headCall,wavCall,hallmarkHit,finalLabel,confidence\n")
        for (id in ids.sorted()) {
            val h = headC.getValue(id); val w = wavC.getValue(id); val hm = hit.getValue(id)
            val votes = (if (h) 1 else 0) + (if (w) 1 else 0) + (if (hm) 1 else 0)
            val finalCough = votes >= 2
            val unanimous = votes == 0 || votes == 3
            if (h && w) { accBoth++; if (hm) accHall++ } else if (!h && !w) { rejBoth++; if (hm) rejHall++ } else { dis++; if (hm) disHall++ }
            if (unanimous) { if (finalCough) uCough++ else uNot++ }
            out.write("$id,${bucketOf[id]},$h,$w,$hm,${if (finalCough) "cough" else "not_cough"},${if (unanimous) "high" else "tiebroken"}\n")
        }
        out.close()
        val n = ids.size
        println()
        println("=== HALLMARK reliability check (hit-rate should track cough-ness) ===")
        println("  2-way ACCEPT (both cough)  n=$accBoth  hallmark-hit ${pct(accHall.toDouble() / accBoth.coerceAtLeast(1))}")
        println("  2-way REJECT (both not)    n=$rejBoth  hallmark-hit ${pct(rejHall.toDouble() / rejBoth.coerceAtLeast(1))}")
        println("  2-way REVIEW (disagree)    n=$dis  hallmark-hit ${pct(disHall.toDouble() / dis.coerceAtLeast(1))}")
        println()
        println("=== TRIAGE — hallmark breaks the tie on the disagreement pile ===")
        println("  BEFORE (head+wavelet 2-way): accept $accBoth (${pct(accBoth.toDouble() / n)}), reject $rejBoth (${pct(rejBoth.toDouble() / n)}), MANUAL REVIEW $dis (${pct(dis.toDouble() / n)})")
        println("  disagree resolved by hallmark → cough $disHall, → not-cough ${dis - disHall}")
        println("  AFTER (majority of 3): high-confidence ${uCough + uNot} (${pct((uCough + uNot).toDouble() / n)}) = cough $uCough + not $uNot; tiebroken ${n - uCough - uNot} (${pct((n - uCough - uNot).toDouble() / n)})")
        println("  → if you trust the majority vote, MANUAL REVIEW drops from ${pct(dis.toDouble() / n)} to 0; spot-check only the 'tiebroken' rows in harvest_triage.csv")
        println("=== wrote harvest_triage.csv ===")
    }

    private fun framesFor(durMs: Int, winMs: Int, hopMs: Int): List<Pair<Int, Int>> {
        if (durMs <= winMs) return if (durMs > 0) listOf(0 to durMs) else emptyList()
        val out = ArrayList<Pair<Int, Int>>(); var s = 0
        while (s < durMs) { val e = (s + winMs).coerceAtMost(durMs); if (e - s >= winMs / 2) out.add(s to e); if (e >= durMs) break; s += hopMs }
        return out
    }

    private fun bucketName(f: File, harvest: File): String =
        (f.parentFile.relativeToOrNull(harvest)?.path ?: f.parentFile.name).replace('\\', '/').ifEmpty { f.parentFile.name }

    private fun rms(pcm: FloatArray, target: Float = 0.1f) { var s = 0.0; for (x in pcm) s += x.toDouble() * x; val r = sqrt(s / pcm.size.coerceAtLeast(1)); if (r > 1e-5) { val g = (target / r).toFloat(); for (i in pcm.indices) pcm[i] *= g } }
    private fun pct(x: Double) = "%.1f%%".format(x * 100)
}
