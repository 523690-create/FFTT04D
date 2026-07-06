package com.example.FFTT04M.desktop

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Re-gate the harvested segments through the trained CoughForest (WholeClipFeatures → CoughForest, ~91%
 * sens / 83% spec) — the gate mobile's real-time capture actually uses — instead of the isLikelyCough DSP
 * heuristic the harvest used. Pure CPU/DSP (no GPU, no HuBERT), all cores. Adds a THIRD independent opinion
 * to the wavelet-image + HuBERT-head comparison and reports the same bag-aware metrics + 3-way consensus.
 *
 * Run: ./gradlew :desktop:forestScore   (needs cough_harvest/harvest_compare.csv for the 3-way join)
 */
object HarvestForestCli {

    @JvmStatic
    fun main(args: Array<String>) {
        val repo = Workspace.repoRoot ?: File(".")
        val harvest = File(System.getProperty("hf.harvest")?.takeIf { it.isNotBlank() } ?: File(repo, "cough_harvest").path)
        if (!harvest.isDirectory) { println("no harvest dir: $harvest"); return }
        if (!CoughClassifier.available()) { println("no cough_forest bundled — CoughClassifier unavailable"); return }
        val thr = CoughClassifier.threshold()
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        println("=== FOREST re-gate over $harvest (CoughForest threshold=${"%.3f".format(thr)}, $cores cores) ===")

        val wavs = harvest.walkTopDown().filter { it.isFile && it.extension.equals("wav", true) }.toList()
        val pForest = ConcurrentHashMap<String, Double>(); val bucketOf = ConcurrentHashMap<String, String>()
        val done = AtomicInteger()
        run {
            val pool = Executors.newFixedThreadPool(cores)
            try {
                wavs.map { wav ->
                    pool.submit {
                        try {
                            if (wav.length() <= 50_000_000L) {
                                val pcm = AudioDecoder.decode(wav)
                                if (pcm != null && pcm.isNotEmpty()) {
                                    pForest[wav.nameWithoutExtension] = CoughClassifier.coughProb(pcm, 44100)
                                    bucketOf[wav.nameWithoutExtension] = bucketName(wav, harvest)
                                }
                            }
                        } catch (_: Exception) {}
                        val n = done.incrementAndGet(); if (n % 10000 == 0 || n == wavs.size) println("  scored $n/${wavs.size}")
                    }
                }.forEach { it.get() }
            } finally { pool.shutdown() }
        }
        File(harvest, "harvest_forest.csv").bufferedWriter().use { w ->
            w.write("id,bucket,pForest\n")
            for (id in pForest.keys.sorted()) w.write("$id,${bucketOf[id]},${"%.3f".format(pForest.getValue(id))}\n")
        }
        println("=== wrote harvest_forest.csv (${pForest.size} scored) ===")

        // ---- 3-way join with the wavelet + head scores already in harvest_compare.csv -----------------
        val comp = File(harvest, "harvest_compare.csv")
        if (!comp.isFile) { println("no harvest_compare.csv — forest scores written, skipping 3-way. Run harvestClassify first."); return }
        val pWav = HashMap<String, Double>(); val pHead = HashMap<String, Double>()
        comp.useLines { lines ->
            lines.drop(1).forEach { ln ->
                val c = ln.split(','); if (c.size < 5) return@forEach
                c[3].toDoubleOrNull()?.let { pWav[c[0]] = it }; c[4].toDoubleOrNull()?.let { pHead[c[0]] = it }
            }
        }
        val ids = pForest.keys.filter { pWav.containsKey(it) && pHead.containsKey(it) }
        if (ids.isEmpty()) { println("no clips share all three scores"); return }

        val segRe = Regex("__cough\\d+_\\d+-\\d+ms$")
        var allC = 0; var allN = 0; var mixed = 0
        var fhAgree = 0; var fwAgree = 0; var whAgree = 0
        var negN = 0; var fFP = 0; var wFP = 0; var hFP = 0
        val bagF = HashMap<String, Boolean>(); val bagH = HashMap<String, Boolean>(); val bagW = HashMap<String, Boolean>()
        val perBucket = HashMap<String, IntArray>()   // [n, forestCough]
        for (id in ids) {
            val fc = pForest.getValue(id) >= thr; val wc = pWav.getValue(id) >= 0.5; val hc = pHead.getValue(id) >= 0.5
            when { fc && wc && hc -> allC++; !fc && !wc && !hc -> allN++; else -> mixed++ }
            if (fc == hc) fhAgree++; if (fc == wc) fwAgree++; if (wc == hc) whAgree++
            val bs = perBucket.getOrPut(bucketOf[id] ?: "?") { IntArray(2) }; bs[0]++; if (fc) bs[1]++
            when (binLabel(id)) {
                0 -> { negN++; if (fc) fFP++; if (wc) wFP++; if (hc) hFP++ }
                1 -> { val s = id.replace(segRe, ""); bagF[s] = (bagF[s] ?: false) || fc; bagH[s] = (bagH[s] ?: false) || hc; bagW[s] = (bagW[s] ?: false) || wc }
            }
        }
        val n = ids.size
        println()
        println("=== 3-WAY (forest + HuBERT-head + wavelet) on $n harvested segments ===")
        println("  ALL THREE cough (high-conf accept)     ${allC.toString().padStart(8)}  (${pct(allC.toDouble() / n)})")
        println("  ALL THREE not-cough (high-conf reject) ${allN.toString().padStart(8)}  (${pct(allN.toDouble() / n)})")
        println("  MIXED (manual review)                  ${mixed.toString().padStart(8)}  (${pct(mixed.toDouble() / n)})")
        println("  pairwise agreement:  forest↔head ${pct(fhAgree.toDouble() / n)}   forest↔wavelet ${pct(fwAgree.toDouble() / n)}   wavelet↔head ${pct(whAgree.toDouble() / n)}")
        println()
        println("=== HARD NEGATIVES (label denies cough, n=$negN) — false-positive rate, lower=better ===")
        if (negN > 0) println("  forest ${pct(fFP.toDouble() / negN)}   head ${pct(hFP.toDouble() / negN)}   wavelet ${pct(wFP.toDouble() / negN)}   ← forest is mobile's real gate")
        println()
        println("=== POSITIVE BAGS (cough present somewhere) — BAG recall, higher=better ===")
        val nb = bagF.size
        if (nb > 0) println("  $nb clips:  forest ${pct(bagF.values.count { it }.toDouble() / nb)}   head ${pct(bagH.values.count { it }.toDouble() / nb)}   wavelet ${pct(bagW.values.count { it }.toDouble() / nb)}")
        println()
        println("=== per-bucket forest cough-call rate ===")
        for ((bk, s) in perBucket.toSortedMap()) if (s[0] > 0)
            println("  ${bk.padEnd(26)} n=${s[0].toString().padStart(7)}  forest→cough ${pct(s[1].toDouble() / s[0])}")
    }

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

    private fun bucketName(f: File, harvest: File): String =
        (f.parentFile.relativeToOrNull(harvest)?.path ?: f.parentFile.name).replace('\\', '/').ifEmpty { f.parentFile.name }

    private fun pct(x: Double) = "%.1f%%".format(x * 100)
}
