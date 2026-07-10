package com.example.FFTT04M.desktop

import com.example.FFTT04M.desktop.cough.MultiRidgeExtractor
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-segment DSP feature pass for the cough-isolation gate (COUGH_ISOLATION.md, signals 4+5) — the two
 * signals ORTHOGONAL to voice content (squiggle chirp + speech cues), which the head/wavelet/forest/
 * hallmark quartet all miss. Computed DIRECTLY on each already-isolated harvest segment WAV (no parent-id
 * join needed: the segment filename == the id used in harvest_compare/forest/hallmark.csv), all cores.
 *
 *   squiggleMaxR2, squiggleCount  ← MultiRidgeExtractor (300–2000 Hz parabolic ridge) on the segment
 *   pitch, flatness, syllabic     ← WholeClipFeatures (pitch_strength / flatness / syllabic_mod)
 *
 * Writes cough_harvest/harvest_dsp.csv (id,squiggleMaxR2,squiggleCount,pitch,flatness,syllabic).
 * Run: ./gradlew :desktop:harvestDsp -Dgate.harvest=G:\cough_harvest
 */
object HarvestDspCli {

    private const val SR = 44100
    private val workers = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    @JvmStatic
    fun main(args: Array<String>) {
        val repo = Workspace.repoRoot ?: File(".")
        val harvest = File(System.getProperty("gate.harvest")?.takeIf { it.isNotBlank() }
            ?: File(repo, "cough_harvest").path)
        if (!harvest.isDirectory) { println("no dir: $harvest"); return }

        val wavs = harvest.walkTopDown().filter { it.isFile && it.extension.equals("wav", true) }.toList()
        val total = wavs.size
        println("=== HARVEST DSP over $harvest — $total segments · $workers cores ===")
        val rows = ConcurrentLinkedQueue<String>()
        val done = AtomicInteger(); val failed = AtomicInteger()
        val startNs = System.nanoTime()

        val pool = Executors.newFixedThreadPool(workers)
        try {
            wavs.map { wav ->
                pool.submit {
                    try {
                        val pcm = if (wav.length() > 50_000_000L) null else AudioDecoder.decode(wav)
                        if (pcm == null || pcm.isEmpty()) failed.incrementAndGet()
                        else {
                            val events = MultiRidgeExtractor().detect(pcm, 0, pcm.size, SR)
                            val sqCount = events.size
                            val sqMaxR2 = events.maxOfOrNull { it.rSquared } ?: 0.0
                            val f = WholeClipFeatures.extract(pcm, SR)      // 14-dim; pull the speech cues
                            val flat = f[WholeClipFeatures.names.indexOf("flatness")]
                            val pitch = f[WholeClipFeatures.names.indexOf("pitch_strength")]
                            val syll = f[WholeClipFeatures.names.indexOf("syllabic_mod")]
                            rows.add("${wav.nameWithoutExtension},${"%.4f".format(sqMaxR2)},$sqCount," +
                                "${"%.4f".format(pitch)},${"%.4f".format(flat)},${"%.4f".format(syll)}")
                        }
                    } catch (e: Exception) {
                        failed.incrementAndGet(); System.err.println("dsp ${wav.name}: ${e.message}")
                    }
                    val d = done.incrementAndGet()
                    if (d % 2000 == 0 || d == total) {
                        val cps = d / ((System.nanoTime() - startNs) / 1e9).coerceAtLeast(1e-9)
                        println("  $d/$total · ${"%.0f".format(cps)} seg/s")
                    }
                }
            }.forEach { it.get() }
        } finally { pool.shutdown() }

        val out = File(harvest, "harvest_dsp.csv")
        out.bufferedWriter().use { w ->
            w.write("id,squiggleMaxR2,squiggleCount,pitch,flatness,syllabic\n")
            for (r in rows.sorted()) { w.write(r); w.write("\n") }
        }
        println("=== HARVEST DSP done: ${rows.size} rows, ${failed.get()} failed, " +
            "${"%.1f".format((System.nanoTime() - startNs) / 1e9)}s → $out ===")
    }
}
