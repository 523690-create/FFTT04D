package com.example.FFTT04M.desktop

import com.example.FFTT04M.desktop.cough.MultiRidgeExtractor
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * WHOLE-CLIP scorer over the full ALLDATA corpus — the segmenter-INDEPENDENT method family for the
 * competitive eval (CoughEvalCli). Unlike the harvest seg-OR methods (all downstream of the DSP
 * segmenter), these score the raw clip directly, so they test the "gate on the whole clip, not on
 * pre-isolated bursts" thesis (memory: CoughForest is ~91/83 on whole clips but over-fires on segments).
 *
 * Per clip (from ALLDATA/metadata.csv `wav`): forest P(cough) [WholeClipFeatures→bundled CoughForest],
 * squiggle maxR²/count [MultiRidgeExtractor 300–2000Hz], and speech cues [pitch/flatness/syllabic].
 * → cough_harvest/cough_wholeclip.csv (id,pForest,sqMaxR2,sqCount,pitch,flatness,syllabic). All cores,
 * no GPU. Run: ./gradlew :desktop:allDataScore -Deval.alldata=D:\AndroidProjects\ALLDATA -Deval.harvest=D:\AndroidProjects\cough_harvest
 */
object AllDataScoreCli {

    private val workers = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    @JvmStatic
    fun main(args: Array<String>) {
        val repo = Workspace.repoRoot ?: File(".")
        val alldata = File(System.getProperty("eval.alldata")?.takeIf { it.isNotBlank() } ?: File(repo, "ALLDATA").path)
        val harvest = File(System.getProperty("eval.harvest")?.takeIf { it.isNotBlank() } ?: File(repo, "cough_harvest").path).apply { mkdirs() }
        val meta = File(alldata, "metadata.csv")
        if (!meta.isFile) { println("missing $meta"); return }
        val forest = CoughForest.loadBundled()
        if (forest == null) { println("no bundled cough_forest — cannot score whole-clip forest"); return }
        val flatIdx = WholeClipFeatures.names.indexOf("flatness")
        val pitchIdx = WholeClipFeatures.names.indexOf("pitch_strength")
        val syllIdx = WholeClipFeatures.names.indexOf("syllabic_mod")

        val wavs = ArrayList<File>(80_000)
        meta.useLines { seq ->
            seq.drop(1).forEach { line ->
                val c = line.split(','); val name = c.getOrNull(0)?.trim().orEmpty()
                if (name.isNotEmpty()) File(alldata, name).takeIf { it.isFile }?.let { wavs.add(it) }
            }
        }
        val total = wavs.size
        println("=== ALLDATA WHOLE-CLIP SCORE — $total clips · $workers cores (forest nFeat=${forest.nFeatures}) ===")
        val rows = ConcurrentLinkedQueue<String>(); val done = AtomicInteger(); val failed = AtomicInteger()
        val startNs = System.nanoTime()

        val pool = Executors.newFixedThreadPool(workers)
        try {
            wavs.map { wav ->
                pool.submit {
                    try {
                        val pcm = if (wav.length() > 50_000_000L) null else AudioDecoder.decode(wav)
                        if (pcm == null || pcm.isEmpty()) failed.incrementAndGet()
                        else {
                            val feat = WholeClipFeatures.extract(pcm, 44100)
                            val pForest = forest.coughProb(feat)
                            val events = MultiRidgeExtractor().detect(pcm, 0, pcm.size, 44100)
                            val sqR2 = events.maxOfOrNull { it.rSquared } ?: 0.0
                            rows.add("${wav.nameWithoutExtension},${"%.4f".format(pForest)},${"%.4f".format(sqR2)},${events.size}," +
                                "${"%.4f".format(feat[pitchIdx])},${"%.4f".format(feat[flatIdx])},${"%.4f".format(feat[syllIdx])}")
                        }
                    } catch (e: Exception) { failed.incrementAndGet(); System.err.println("wc ${wav.name}: ${e.message}") }
                    val d = done.incrementAndGet()
                    if (d % 2000 == 0 || d == total)
                        println("  $d/$total · ${"%.0f".format(d / ((System.nanoTime() - startNs) / 1e9).coerceAtLeast(1e-9))} clip/s")
                }
            }.forEach { it.get() }
        } finally { pool.shutdown() }

        val out = File(harvest, "cough_wholeclip.csv")
        out.bufferedWriter().use { w ->
            w.write("id,pForest,sqMaxR2,sqCount,pitch,flatness,syllabic\n")
            for (r in rows.sorted()) { w.write(r); w.write("\n") }
        }
        println("=== done: ${rows.size} rows, ${failed.get()} failed, ${"%.1f".format((System.nanoTime() - startNs) / 1e9)}s → $out ===")
    }
}
