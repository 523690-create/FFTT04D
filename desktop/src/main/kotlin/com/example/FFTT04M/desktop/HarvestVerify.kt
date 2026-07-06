package com.example.FFTT04M.desktop

import com.example.FFTT04M.desktop.fractionation.HubertKMeansUnits
import com.google.gson.Gson
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Auto-assessment verification of harvested coughs: run the trained cough head (HuBERT whole-clip
 * embedding → P(cough)) over every WAV in a folder and move the ones the model DOESN'T think are coughs
 * into `_rejected_lowP/`, leaving a clean review pile + `..._verified.csv`. Aimed at the `found_in_other`
 * bucket, where the cheap DSP detector over-fires on speech — the head is in-distribution for ALLDATA
 * (trained on coswara/urban8k), so it filters those speech syllables out.
 *
 * Run: ./gradlew :desktop:verifyHarvest -Dverify.dir=D:\AndroidProjects\cough_harvest\cough_found_in_other -PuseOnnxGpu
 */
object HarvestVerify {
    private const val SR = 44100

    @JvmStatic
    fun main(args: Array<String>) {
        val repo = Workspace.repoRoot ?: File(".")
        val dir = File(args.getOrNull(0)?.takeIf { it.isNotBlank() }
            ?: System.getProperty("verify.dir")?.takeIf { it.isNotBlank() }
            ?: File(repo, "cough_harvest/cough_found_in_other").path)
        if (!dir.isDirectory) { println("no dir: $dir"); return }
        val threshold = System.getProperty("verify.threshold")?.toDoubleOrNull() ?: 0.5

        val hf = File(Workspace.dir("codebooks"), "cough_head_ALLDATA.json")
        if (!hf.isFile) { println("no head at $hf — train it first (unsupervisedCluster -Dcluster.segbinary)"); return }
        @Suppress("UNCHECKED_CAST")
        val h = Gson().fromJson(hf.readText(), Map::class.java) as Map<String, Any?>
        fun arr(k: String) = (h[k] as List<*>).map { (it as Number).toDouble() }.toDoubleArray()
        val mean = arr("mean"); val std = arr("std"); val b = arr("b")
        val w = (h["w"] as List<*>).map { r -> (r as List<*>).map { (it as Number).toDouble() }.toDoubleArray() }
        val classes = (h["classes"] as? List<*>)?.map { it.toString() } ?: listOf("not_cough", "cough")
        val ci = classes.indexOf("cough").coerceAtLeast(1)
        fun pCough(emb: DoubleArray): Double {
            val z = DoubleArray(emb.size) { (emb[it] - mean[it]) / std[it] }
            val lg = DoubleArray(w.size) { k -> var s = b[k]; for (j in z.indices) s += w[k][j] * z[j]; s }
            val mx = lg.max(); var sum = 0.0; val p = DoubleArray(lg.size) { val e = exp(lg[it] - mx); sum += e; e }
            return p[ci] / sum
        }

        if (!HubertKMeansUnits.available) { println("HuBERT unavailable: ${HubertKMeansUnits.unavailableReason}"); return }
        val wavs = dir.listFiles { f -> f.isFile && f.extension.equals("wav", true) }?.toList() ?: emptyList()
        println("=== VERIFY ${wavs.size} harvested segments in $dir (keep P(cough) >= $threshold) · HuBERT ${HubertKMeansUnits.provider} ===")
        val rejDir = File(dir, "_rejected_lowP").apply { mkdirs() }
        val fail = AtomicInteger(); val done = AtomicInteger()
        // (name, pCough) scored in parallel. Files are NOT moved here — a concurrent renameTo() on this
        // mountvol/NVMe volume silently returns false ~85% of the time, so the physical filtering is done
        // in a reliable sequential nio pass AFTER all decode handles are closed.
        val scored = ConcurrentLinkedQueue<Pair<String, Double>>()

        // small pool: the GPU serialises the HuBERT calls, but decode + head overlap on the CPU.
        val pool = Executors.newFixedThreadPool(4)
        try {
            wavs.map { wav ->
                pool.submit {
                    try {
                        val pcm = AudioDecoder.decode(wav)?.also { rms(it) }
                        if (pcm == null || pcm.size < SR / 16 || pcm.size > SR * 10) fail.incrementAndGet()
                        else {
                            val fe = HubertKMeansUnits.frameEmbeddings(pcm, SR)
                            if (fe == null || fe.isEmpty()) fail.incrementAndGet()
                            else {
                                val d = fe[0].size; val v = DoubleArray(d)
                                for (f in fe) for (j in 0 until d) v[j] += f[j]; for (j in 0 until d) v[j] /= fe.size
                                scored.add(wav.name to pCough(v))
                            }
                        }
                    } catch (e: Exception) { fail.incrementAndGet() }
                    val n = done.incrementAndGet()
                    if (n % 500 == 0 || n == wavs.size) println("  scored $n/${wavs.size}")
                }
            }.forEach { it.get() }
        } finally { pool.shutdown() }

        File(dir.parentFile, "${dir.name}_verified.csv").bufferedWriter().use { ww ->
            ww.write("file,pCough\n"); for ((name, p) in scored.sortedBy { it.first }) ww.write("$name,${"%.3f".format(p)}\n")
        }

        // Reliable sequential move: nio.Files.move throws (and reports) on real failure instead of the
        // renameTo() boolean that we can't trust here.
        var kept = 0; var moved = 0; var moveFail = 0
        for ((name, p) in scored) {
            if (p >= threshold) { kept++; continue }
            val src = File(dir, name)
            try {
                if (src.isFile) { java.nio.file.Files.move(src.toPath(), File(rejDir, name).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING); moved++ }
            } catch (e: Exception) { moveFail++; if (moveFail <= 5) System.err.println("move $name: ${e.message}") }
        }
        println("=== VERIFY complete: kept $kept (P>=$threshold), moved $moved → _rejected_lowP/, moveFailed $moveFail, scoreFailed ${fail.get()} ===")
    }

    private fun rms(pcm: FloatArray, target: Float = 0.1f) {
        var s = 0.0; for (x in pcm) s += x.toDouble() * x
        val r = sqrt(s / pcm.size.coerceAtLeast(1)); if (r > 1e-5) { val g = (target / r).toFloat(); for (i in pcm.indices) pcm[i] *= g }
    }
}
