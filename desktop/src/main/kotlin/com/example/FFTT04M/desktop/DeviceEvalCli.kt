package com.example.FFTT04M.desktop

import com.example.FFTT04M.desktop.cough.MultiRidgeExtractor
import com.google.gson.JsonParser
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Step 2 — evaluate on the USER'S OWN device recordings, where the MANUAL label is the HARD truth
 * (condition 2). These are the M-app grabber captures (ids `cough_<ts>_<ms>` / `<ts>`) in p3/<device>/
 * FFTT04M and device_ingest, manually corrected in data/manual_comments.json (cough→POS; snore/voice/
 * noise→NEG). This is the IN-DOMAIN test the ALLDATA head can't give (memory: the ALLDATA head is OOD on
 * the user's own voice). No GPU: scores the deployable whole-clip forest [CoughClassifier] + squiggle +
 * speech cues per clip, and reports recall on manual-cough vs FP on manual-{snore,voice,noise}.
 *
 * Run: ./gradlew :desktop:deviceEval   (defaults to <repo>/p3 + <repo>/device_ingest, <workspace>/data/manual_comments.json)
 */
object DeviceEvalCli {

    private val workers = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    @JvmStatic
    fun main(args: Array<String>) {
        val repo = Workspace.repoRoot ?: File(".")
        val dirs = (System.getProperty("device.dir")?.split(File.pathSeparator)?.map { File(it) }
            ?: listOf(File(repo, "p3"), File(repo, "device_ingest"))).filter { it.isDirectory }
        val mcFile = File(System.getProperty("device.manual")?.takeIf { it.isNotBlank() } ?: Workspace.file("manual_comments.json").path)
        if (!mcFile.isFile) { println("missing manual_comments.json at $mcFile"); return }
        val forest = CoughForest.loadBundled() ?: run { println("no bundled forest"); return }

        val manual = HashMap<String, String>()
        runCatching { JsonParser.parseString(mcFile.readText()).asJsonObject }.getOrNull()?.entrySet()?.forEach {
            if (it.value.isJsonPrimitive) manual[it.key] = it.value.asString
        }
        val flatIdx = WholeClipFeatures.names.indexOf("flatness")
        val pitchIdx = WholeClipFeatures.names.indexOf("pitch_strength")
        val syllIdx = WholeClipFeatures.names.indexOf("syllabic_mod")

        // labelled device clips (id → truth + label), matched by wav basename
        data class Item(val wav: File, val truth: CoughTruth.Truth, val label: String)
        val items = ArrayList<Item>()
        for (d in dirs) d.walkTopDown().forEach { f ->
            if (f.isFile && f.extension.equals("wav", true)) {
                val lab = manual[f.nameWithoutExtension] ?: return@forEach
                val t = CoughTruth.fromManual(lab)
                if (t != CoughTruth.Truth.SKIP) items.add(Item(f, t, lab.lowercase().trim()))
            }
        }
        val nPos = items.count { it.truth == CoughTruth.Truth.POS }; val nNeg = items.size - nPos
        println("=== DEVICE EVAL (manual hard labels, condition 2) ===")
        println("dirs ${dirs.map { it.name }} · labelled device clips ${items.size} (cough=$nPos, not-cough=$nNeg)")
        if (nPos < 20 || nNeg < 20) { println("insufficient labelled device data"); return }

        // score whole-clip (no GPU), parallel
        val rows = ConcurrentLinkedQueue<Triple<Item, Double, Double>>()  // item, pForest, sqMaxR2
        val done = AtomicInteger(); val pool = Executors.newFixedThreadPool(workers)
        try {
            items.map { it -> pool.submit {
                runCatching {
                    val pcm = AudioDecoder.decode(it.wav)
                    if (pcm != null && pcm.isNotEmpty()) {
                        val feat = WholeClipFeatures.extract(pcm, 44100)
                        val pF = forest.coughProb(feat)
                        val sq = MultiRidgeExtractor().detect(pcm, 0, pcm.size, 44100).maxOfOrNull { e -> e.rSquared } ?: 0.0
                        rows.add(Triple(it, pF, sq))
                    }
                }
                done.incrementAndGet()
            } }.forEach { it.get() }
        } finally { pool.shutdown() }

        // forest@wc recall/FP sweep
        val scored = rows.toList()
        println("\n-- whole-clip forest on device recordings --   recall   FP")
        for (t in listOf(0.3, 0.4, 0.5, 0.6, 0.7, 0.8)) {
            val pos = scored.filter { it.first.truth == CoughTruth.Truth.POS }
            val neg = scored.filter { it.first.truth == CoughTruth.Truth.NEG }
            val rec = pos.count { it.second >= t }.toDouble() / pos.size.coerceAtLeast(1)
            val fp = neg.count { it.second >= t }.toDouble() / neg.size.coerceAtLeast(1)
            println("  thr %.2f   %6.1f%%  %6.1f%%".format(t, rec * 100, fp * 100))
        }
        // per-negative-label FP @0.5 (which real-world sounds fool the grabber?)
        println("\n-- FP by manual not-cough label @forest 0.5 --")
        scored.filter { it.first.truth == CoughTruth.Truth.NEG }.groupBy { keyOf(it.first.label) }
            .toList().sortedByDescending { it.second.size }.forEach { (lab, g) ->
                println("  %-10s %5.1f%%  (n=%d)".format(lab, 100.0 * g.count { it.second >= 0.5 } / g.size, g.size))
            }

        val out = File(dirs.first().parentFile ?: repo, "device_eval.csv")
        out.bufferedWriter().use { w ->
            w.write("id,label,truth,pForest,sqMaxR2\n")
            for ((it, pf, sq) in scored) w.write("${it.wav.nameWithoutExtension},${it.label.replace(',', ';')},${it.truth},${"%.3f".format(pf)},${"%.3f".format(sq)}\n")
        }
        println("\nwrote → $out")
        println("NOTE: whole-clip forest is DSP-only (no HuBERT), so it's the least OOD signal on user voice. " +
            "Full clip-gate device eval needs a device HuBERT embedding pass (GPU) for head+specialist.")
    }

    private fun keyOf(lab: String) = when {
        "snor" in lab -> "snore"
        "voice" in lab || "speech" in lab || "talk" in lab -> "voice"
        "noise" in lab -> "noise"
        else -> lab.take(10)
    }
}
